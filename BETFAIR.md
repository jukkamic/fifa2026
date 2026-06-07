# Setting up Betfair API keys

## OpenSSL

The following instructions are modified for my workspace based on <https://betfair-developer-docs.atlassian.net/wiki/spaces/1smk3cen4v3lu3yomq5qye0ni/pages/2687915/Non-Interactive+bot+login>

### Update or Create the openssl configuration file (openssl.cnf) for OpenSSL to override some of the default settings: 

In the project's *ssl/* directory in GitBash run

```bash
cp /mingw64/ssl/openssl.cnf .
```

Update or create the sll_client section

```txt
[ ssl_client ]
basicConstraints = CA:FALSE
nsCertType = client
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
```

### Create a certificate signing request (CSR).

The cert creation asked a lot of questions. I just wrote FI for country and Helsinki for the state, then enter enter enter (no passwords).

```bash
openssl genrsa -out client-2048.key 2048

openssl req -new -config openssl.cnf -key client-2048.key -out client-2048.csr

openssl x509 -req -days 365 -in client-2048.csr -signkey client-2048.key -out client-2048.crt
```

Upload the .crt file to your Betfair account dashboard at <https://myaccount.betfair.com/accountdetails/mysecurity?showAPI=1>
