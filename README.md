# Drill Fix (Fabric 26.1.2)

Klientowy mod: kopanie nie resetuje się, gdy serwer zmieni dane trzymanego przedmiotu
(paliwo drilla na Hypixel Skyblock, naprawa przez Mending). Odpowiednik HSM dla 26.1.x.

Budowanie: GitHub Actions robi to samo po każdym pushu. Gotowy `.jar` jest w zakładce
**Actions → ostatni run → Artifacts → drillfix**.

Lokalnie: `./gradlew build` (wymaga JDK 25), plik w `build/libs/`.
