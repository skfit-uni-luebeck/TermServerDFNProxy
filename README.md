## Proxy for the central Ontoserver in Germany

[![Docker Repository on Quay](https://quay.io/repository/itcrl/termserver-dfn-proxy/status "Docker Repository on Quay")](https://quay.io/repository/itcrl/termserver-dfn-proxy)

The current central Ontoserver instance for the German Medizininformatik-Initiative (MI-I)
hosted in Cologne, available at https://terminology-highmed.medic.medfak.uni-koeln.de/,
is secured by Mutual TLS Authentication. The service is available to individuals and
organizations within the MI-I, as partners of and actors in the 4 consortia,
within POLAR\_MI and CORD-MI, and within the Network University Medicine (NUM).

Only authenticated clients can access resources on this server.
You will need to obtain a certificate issued within the
Deutsches Forschungsnetz-Private Key infractructure (DFN-PKI)
with the profile "User" or "802.1X Client" to use this service.

To consume resources on this server, you should make sure that
your client code is aware of this restriction and apply for such
a certificate. Contact your universities' IT department for assistance
when applying for such a certificate.

This project can help you to access the central server with clients that are
unable to authenticate using TLS certificates themselves. It serves as a reverse
proxy that sits in front of the internet that routes all requests to it to the central
instance, with the body intact. During this step, it will present the configured
server certificate to the Ontoserver in Cologne. Communication with this reverse proxy
can be done over HTTP, while it will communicate with the central instance over HTTPS.

The software is written in Kotlin using the ktor.io framework (version 1.4.1) both to
host the server and to make client requests. When requesting plain-text resources (MIME
types matching `/(application|text)/(fhir|atom)?+?(json|xml|plain|html)/`), all occurrences
of the configured upstream url are rewritten to point to the proxy. This makes syndication
possible using this proxy, if your local Ontoserver points at this proxy.

To set up this system, you will a current Java Development Kit, e.g. from https://azul.com
The project is built using Gradle. You will also need your certificate and private key in a format
that can be consumed by the JDK (PKCS12 or JKS format recommended, you can use https://keystore-explorer.org/ for
converting to these formats).

## Docker

This app is also available from the Docker registry *quay.io*.

You can use the included docker-compose to get started. Beforehand, you will need to copy the configuration template
in [`resources/proxy.conf.example`](resources/proxy.conf.example) to a suitable location (
e.g. [`resources/proxy.conf`](resources/proxy.conf)),
and maybe change the bind mound path accordingly in the docker-compose file.

By default, the application will be exposed on port 4242, and you can change this in the docker-compose file.

For a permanent deployment, you will need to also adjust the public address in the configuration file to match your
hostname, so that the proxy can rewrite the URLs in the responses from the central server correctly. The app also
supports HTTPS, and it's strongly recommended that you use it. To do so, you will need to provide a certificate and
configure the appropriate configurations. It is also recommended to redirect all HTTP requests to HTTPS, and [enabling
HTTP Strict Transport Security (HSTS)](https://https.cio.gov/hsts/). This is supported directly in-app, but some users
may want to deploy the proxy behind a reverse proxy that handles HTTPS and HSTS for them. In this case, you will also
need to configure the URL parameters for HTTPS, so that the app can rewrite the URLs to the correct endpoint. Note that
this app only supports deployments behind a reverse proxy for HTTPS. You connect to the reverse proxy via HTTP, but
the proxy will always report HTTPS urls to the client.

If you need to change the build architecture (e.g. when running on an ARM-based system), it is recommended to build the
docker container from source. Simply uncomment the following line from the docker-compose file:

```
    #    build: .
```