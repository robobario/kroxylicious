#!/bin/bash
SCRIPT_DIR=$( cd -- "$( dirname -- "${BASH_SOURCE[0]}" )" &> /dev/null && pwd )
INGRESS_DOMAIN="$(oc get ingresses.config/cluster -o jsonpath={.spec.domain})"
IMAGE=${KROXYLICIOUS_IMAGE:-quay.io/robeyoun/kroxylicious:0.10.0-SNAPSHOT}
find ${SCRIPT_DIR} -type f -name '*.yaml' -exec sed -i "s/\\\$\\\$INGRESS_DOMAIN\\\$\\\$/${INGRESS_DOMAIN}/g" {} \;
sed -i "s#KROXYLICIOUS_IMAGE#${IMAGE}#g" "${SCRIPT_DIR}/06-kroxylicious-deployment.yaml"
