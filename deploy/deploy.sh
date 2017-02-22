#!/bin/bash
set -e

if [[ "$1" == "" ]]; then
    echo "usage: $0 <docker_tag>"
    exit -1
fi

export DOCKER_TAG=$1
export JOB_NAME="${JOB_NAME-director}"
export VAULT_ENDPOINT=${VAULT_ENDPOINT-$(echo $JOB_NAME | tr "-" "_")}
export IMAGE_NAME="director"
export REGISTRY="advancedtelematic"
export IMAGE_ARTIFACT=${REGISTRY}/${IMAGE_NAME}:${DOCKER_TAG}
export USE_MEM="1024.0"
export USE_CPU="0.5"
export JAVA_OPTS="-Xmx800m"

export MARATHON="http://marathon.prod01.internal.advancedtelematic.com:8080"

cat deploy/service.json |
    envsubst |
    python2 deploy/add-vault-vars.py $VAULT_ENDPOINT $VAULT_TOKEN |
    tee marathon_deploy.log |
    curl --show-error --fail \
         --header "Content-Type: application/json" \
         --request PUT \
         --data @- \
         ${MARATHON}/v2/apps
