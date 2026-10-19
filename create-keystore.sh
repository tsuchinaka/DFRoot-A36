#!/bin/sh

set -eux
if [ -e app/keystore.jks ]; then
    echo Already exist.
else
    keytool -genkey -v -keystore app/keystore.jks -keyalg RSA -storetype PKCS12 -keysize 2048 -validity 10000 \
        -alias dfroot -storepass android -keypass android \
        -dname "CN=DFRoot A36, OU=root, O=local, C=US"
    echo Generated on app/keystore.jks
fi
