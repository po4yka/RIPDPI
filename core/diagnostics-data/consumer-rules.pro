# dnsjava's Java 8 resolver SPI has no Android implementation. Only its wire
# codec is used here. Fail shrinking if the desktop provider becomes reachable.
-dontwarn sun.net.spi.nameservice.NameServiceDescriptor
-checkdiscard class org.xbill.DNS.spi.DNSJavaNameServiceDescriptor
