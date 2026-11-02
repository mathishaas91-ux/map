# MapInSlot (Paper)

Serverseitige Variante der Fabric-Mod "Map in Slot": Karten im Inventar zeigen ihren echten Inhalt.

## Bauen
    mvn package
Die JAR liegt danach in `target/MapInSlot-1.0.0.jar` -> in `plugins/` kopieren.

## Einrichten
1. Server einmal starten, dann `plugins/MapInSlot/config.yml` oeffnen.
2. `http.public-url` auf `http://<deine-IP-oder-Domain>:8765/mapinslot.zip` setzen.
3. TCP-Port 8765 (oder deinen gewaehlten) in Firewall/Router freigeben.
4. `pack.format` passend zur Minecraft-Version einstellen.
5. `/mapinslot rebuild`

Voraussetzung: Minecraft/Paper 1.21.4 oder neuer (Item-Model-Komponente).
