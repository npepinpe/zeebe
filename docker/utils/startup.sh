#!/bin/bash -xeu

# legacy support
# This environment variable was used to set the gatewway cluster host in standalone and embedded mode.
# Now, there are two dedicated environment variables for the two different deployment scenarios.
# Print only first address because if we use an IPv6 there could be more than one addresses
export ZEEBE_HOST=${ZEEBE_HOST:-$(hostname -i | awk '{print $1}')}
# Legacy support

if [ "$ZEEBE_STANDALONE_GATEWAY" = "true" ]; then
    export ZEEBE_GATEWAY_CLUSTER_HOST=${ZEEBE_GATEWAY_CLUSTER_HOST:-${ZEEBE_HOST}}

    exec /usr/local/zeebe/bin/gateway
else
    export ZEEBE_BROKER_NETWORK_HOST=${ZEEBE_BROKER_NETWORK_HOST:-${ZEEBE_HOST}}
    export ZEEBE_BROKER_GATEWAY_CLUSTER_HOST=${ZEEBE_BROKER_GATEWAY_CLUSTER_HOST:-${ZEEBE_BROKER_NETWORK_HOST}}

    exec /usr/local/zeebe/bin/broker
fi
