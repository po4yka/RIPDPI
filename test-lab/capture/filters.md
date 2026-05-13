# Capture Filters

- Phone all traffic: `host ${PHONE_IP}`
- DNS: `host ${PHONE_IP} and port 53`
- HTTP lab: `host ${PHONE_IP} and port 8080`
- HTTPS lab: `host ${PHONE_IP} and port 8443`
- TCP echo: `host ${PHONE_IP} and port 9000`
- UDP echo: `host ${PHONE_IP} and udp port 9001`
- QUIC: `host ${PHONE_IP} and udp port 9443`
