#!/bin/bash
set -e
if [[ -n ${1} ]]; then
    echo "seeding maven cache with ref ${1}";
    if ! command -v git 2>&1 >/dev/null
    then
        echo "installing git";
        microdnf -y update;
        microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y git;
    fi
    echo "shallow cloning ref ${1}"
    git clone --branch "${1}" --depth 1  https://github.com/kroxylicious/kroxylicious baseliner;
    cd baseliner;
    echo "running maven dependency:go-offline to seed local cache"
    mvn -q -B dependency:go-offline -Daether.dependencyCollector.impl=bf -Dos.detected.classifier=linux-x86_64 -Dos.detected.name=linux -Dos.detected.arch=x86_64;
    mvn -q -B dependency:go-offline -Daether.dependencyCollector.impl=bf -Dos.detected.classifier=osx-aarch_64 -Dos.detected.name=osx -Dos.detected.arch=aarch_64;
    cd ..;
    echo "cleaning up temporary checkout"
    rm -rf baseliner;
else
    echo "no tag supplied, skipping seeding maven cache"
fi;
