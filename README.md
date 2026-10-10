# Roadside Parking Traffic Simulation

A Java/JavaFX team project for EE364 at King Abdulaziz University. It simulates traffic on a multi-lane street with roadside parking and compares unmanaged parking with a managed parking approach.

## Features

- **Phase 1:** Vehicles search for parking and may wait in the curb lane, blocking traffic.
- **Phase 2:** Parking reservations, early redirection, and police patrol manage parking demand.
- Replays the same traffic demand to compare waiting time, congestion, speed, and throughput.
- JavaFX visualization with a phase selector and live statistics.
- Demonstrates interfaces, inheritance, and polymorphism.

## Run

Install JDK 21 or newer and download the JavaFX 21 SDK for your operating system and CPU architecture. Run these commands from the project root on macOS or Linux, replacing the SDK path:

```bash
export JAVAFX_LIB="/path/to/javafx-sdk-21/lib"
mkdir -p out
find src -name '*.java' > sources.txt
javac --module-path "$JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -d out @sources.txt
java --module-path "$JAVAFX_LIB" --add-modules javafx.controls,javafx.graphics -cp out traffic.Main
```

Start with Phase 1, then select Phase 2 to replay the recorded traffic under managed parking rules. Keep the vehicle sprite folder in the project root.

## Files

- `src/` — Java source code.
- `2D TOP DOWN PIXEL CARS/` — vehicle sprites.
- `output/` — example comparison reports.
- `uml/` — class diagrams.
- `Read/` — original team documentation and credits.
- `docs/` — generated Javadoc.

The bundled Windows JavaFX SDK and compiled class files are excluded from version control. See `Read/README.md` for the original project documentation and team members.
