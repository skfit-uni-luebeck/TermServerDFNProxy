## Proxy for the central Ontoserver in Germany

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

This project can help you accessing the central server with clients that are
unable to authenticate using TLS certificates themselves. It serves as a reverse
proxy that sits in front of the internet that routes all requests to it to the central
instance, with the body intact. During this step, it will present the configured
server certificate to the Ontoserver in Cologne. Communication with this reverse proxy
can be done over HTTP, while it will communicate with the central instance over HTTPS.

The software is written in Kotlin using the ktor.io framework (version 1.4.1) both to
host the server and to make client requests. When requesting plain-text resources (MIME
types matching `/(application|text)/(fhir|atom)?+?(json|xml|plain|html)/`), all occurences
of the configured upstream url are rewritten to point to the proxy. This makes syndication
possible using this proxy, if your local Ontoserver points at this proxy.

To set up this system, you will a current Java Development Kit, e.g. from https://adoptopenjdk.net/
The project is built using Gradle. You will also need your certificate and private key in a format
that can be consumed by the JDK (PKCS12 or JKS format recommended, you can use https://keystore-explorer.org/ for converting to these formats). 
