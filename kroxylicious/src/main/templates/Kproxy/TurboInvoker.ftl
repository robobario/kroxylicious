<#--

    Copyright Kroxylicious Authors.

    Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0

-->
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ${outputPackage};

import org.apache.kafka.common.protocol.ApiKeys;

import io.kroxylicious.proxy.frame.DecodedRequestFrame;
import io.kroxylicious.proxy.frame.DecodedResponseFrame;

<#list messageSpecs as messageSpec>
    import org.apache.kafka.common.message.${messageSpec.name}Data;
</#list>
<#list messageSpecs as messageSpec>
import io.kroxylicious.proxy.filter.${messageSpec.name}Filter;
</#list>

import java.util.HashSet;
import java.util.EnumSet;
import java.util.Set;

public class TurboInvoker implements KrpcFilter
{
    private final Set<ApiKeys> requestKeys;
    private final Set<ApiKeys> responseKeys;
    private final RequestTargeter requestTargeter;
    private final ResponseTargeter responseTargeter;

<#list messageSpecs as messageSpec>
    private final ${messageSpec.name}Filter ${messageSpec.name}Filter;
</#list>
    public TurboInvoker(Object filter){
        requestTargeter = filter instanceof RequestTargeter ? (RequestTargeter) filter: null;
        responseTargeter = filter instanceof ResponseTargeter ? (ResponseTargeter) filter: null;
        Set<ApiKeys> supportedRequestKeys = new HashSet<>();
        Set<ApiKeys> supportedResponseKeys = new HashSet<>();
<#list messageSpecs as messageSpec>
    ${messageSpec.name}Filter = filter instanceof ${messageSpec.name}Filter ? (${messageSpec.name}Filter) filter : null;
    <#if messageSpec.type?lower_case == 'request'>
        if(filter instanceof ${messageSpec.name}Filter) {
          supportedRequestKeys.add(ApiKeys.${retrieveApiKey(messageSpec)});
        }
    </#if>
    <#if messageSpec.type?lower_case == 'response'>
        if(filter instanceof ${messageSpec.name}Filter) {
          supportedResponseKeys.add(ApiKeys.${retrieveApiKey(messageSpec)});
        }
    </#if>
</#list>
        requestKeys = supportedRequestKeys.isEmpty() ? EnumSet.noneOf(ApiKeys.class): EnumSet.copyOf(supportedRequestKeys);
        responseKeys = supportedResponseKeys.isEmpty() ? EnumSet.noneOf(ApiKeys.class): EnumSet.copyOf(supportedResponseKeys);
    }

public void onRequest(DecodedRequestFrame<?> decodedFrame,
KrpcFilterContext filterContext) {
switch (decodedFrame.apiKey()) {
<#list messageSpecs as messageSpec>
    <#if messageSpec.type?lower_case == 'request'>
        case ${retrieveApiKey(messageSpec)}:
        ${messageSpec.name}Filter.on${messageSpec.name}((DecodedRequestFrame<${messageSpec.name}Data>) decodedFrame, filterContext);
        break;
    </#if>
</#list>
default:
throw new IllegalStateException("Unsupported RPC " + decodedFrame.apiKey());
}
}

public void onResponse(DecodedResponseFrame<?> decodedFrame,
KrpcFilterContext filterContext) {
switch (decodedFrame.apiKey()) {
<#list messageSpecs as messageSpec>
    <#if messageSpec.type?lower_case == 'response'>
        case ${retrieveApiKey(messageSpec)}:
        ${messageSpec.name}Filter.on${messageSpec.name}((DecodedResponseFrame<${messageSpec.name}Data>) decodedFrame, filterContext);
        break;
    </#if>
</#list>
default:
throw new IllegalStateException("Unsupported RPC " + decodedFrame.apiKey());
}
}

    public boolean shouldDeserializeRequest(ApiKeys apiKey, short apiVersion) {
        return requestKeys.contains(apiKey) && (requestTargeter == null || requestTargeter.shouldDeserializeRequest(apiKey, apiVersion));
    }

    public boolean shouldDeserializeResponse(ApiKeys apiKey, short apiVersion) {
        return responseKeys.contains(apiKey) && (responseTargeter == null || responseTargeter.shouldDeserializeResponse(apiKey, apiVersion));
    }

    public static boolean implementsAnyMessageInterface(Object obj){
    <#list messageSpecs as messageSpec>
        if(obj instanceof ${messageSpec.name}Filter){
            return true;
        }
    </#list>
        return false;
    }

}