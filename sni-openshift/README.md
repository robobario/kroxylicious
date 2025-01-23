1. create openshift cluster
2. login with oc
3. install cert-manager and Streams for Apache Kafka Operators through the UI
4. run `KROXYLICIOUS_IMAGE=quay.io/yourorg/kroxylicious:0.12.0-SNAPSHOT update.sh` and check that the certificate, config and routes are updated with the ingress domain for your cluster (`oc get ingresses.config/cluster -o jsonpath={.spec.domain}` to get the domain)
6. oc apply -f .
7. copy kafka cluster certificates once they appear: `${KUBECTL} -n kafka get secrets -n kafka -l strimzi.io/component-type=certificate-authority -o json | jq '.items[] |= ( .metadata |= {name} )' | ${KUBECTL} apply -n kroxylicious -f -`

To produce:

```
INGRESS_DOMAIN="$(oc get ingresses.config/cluster -o jsonpath={.spec.domain})"
kafka-console-producer.sh --bootstrap-server "my-proxy-bootstrap.${INGRESS_DOMAIN}:443" --topic my-topic --producer-property ssl.truststore.type=PEM --producer-property security.protocol=SSL --producer-property ssl.truststore.location=<(kubectl get secret -n kroxylicious kroxy-server-key-material -o json | jq -r ".data.\"tls.crt\" | @base64d")
```

To consume:

```
INGRESS_DOMAIN="$(oc get ingresses.config/cluster -o jsonpath={.spec.domain})"
kafka-console-consumer.sh --bootstrap-server "my-proxy-bootstrap.${INGRESS_DOMAIN}:443" --topic my-topic --consumer-property ssl.truststore.type=PEM --consumer-property security.protocol=SSL --from-beginning --consumer-property ssl.truststore.location=<(kubectl get secret -n kroxylicious kroxy-server-key-material -o json | jq -r ".data.\"tls.crt\" | @base64d")
```
