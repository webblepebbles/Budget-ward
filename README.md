# Budget ward

welcome to budget ward, a very cheap hospital sim.

## to play

if you have Java 22 installed, run:

```sh
./gradlew run
```

on macOS, the game needs a Java 22 JVM and Vulkan support.
if you do not have Java, install a Java 22 JDK first.

on Windows, run:

```bat
gradlew.bat run
```

## controls

- `WASD` move
- `Mouse` look around
- `Space` jump
- `Left Shift` sprint
- `E` interact / hold to perform actions
- `Tab` open a nearby patient's chart
- `1`-`5` select tools
- `R` respawn
- `P` pause or resume
- `Esc` release the mouse or close the chart

## the important bit

check patients in at reception, lead them to a free ward bed, and open their chart with `Tab`.
stamp the correct organ, pick the matching symptom, and diagnose them.
then hold `E` at their bed to treat them, request an operating room when needed,
and lead patients upstairs to surgery.

take supplies from the supply station, and discharge patients at the hub counter
(or from their chart). fully treating a patient gets the full payment.

the goal is shown on screen. discharge enough patients to finish the shift.

theres a tutorial in game, enjoy :>

## for developers

run the test suite with:

```sh
./gradlew test
```

shaders are compiled automatically during the build. the project uses Gradle,
Java 22, LWJGL, JOML, and Vulkan.
