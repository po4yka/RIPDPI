# Client import fixtures

`ripdpi-bundle.hysteria-port-hopping.json` is an import test vector adapted to bundle schema version 2. The nominal port 443 is outside the destination set 20000:21000; import must preserve the set, 45-second interval, TLS identity, and Salamander credentials. This vector does not prove that the current deployer can emit hopping profiles.
