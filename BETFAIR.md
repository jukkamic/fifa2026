# Setting up Betfair API keys

## API configurations

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

### Betfair API key

Open the Demo Tool: Go to the Betfair Accounts API Demo Tool (developer.betfair.com/exchange-api/accounts-api-demo/).

Log In: Open a second tab in that same browser and log into your standard Betfair account.

Sync the Token: Go back to the Demo Tool tab and refresh the page. This automatically grabs your active login session and fills in the Session Token box.

Fetch the Keys: In the top left "Operations" menu, click on getDeveloperAppKeys. (If you accidentally deleted them in the past, you can click createDeveloperAppKeys instead).

Execute: Scroll down and click the Execute button at the bottom of the tool.

Your keys will pop up on the right side of the screen! You will likely see two: a "Delayed" key and a "Live" key. For your development and testing with Cline, use the Delayed App Key, as the Live one requires an activation fee and manual approval.

Grab that string, drop it into your .env file for BETFAIR_API_KEY, and let's see if that test finally turns green!

## Testing

```pwsh
.\gradlew.bat test --tests "dev.scaffoldkit.fifa.betfair.BetfairConnectionTest"
```
