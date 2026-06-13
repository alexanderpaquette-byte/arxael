rootProject.name = "arxael"

// Single-module for now. The adapter SPI lives in :core; future language adapters
// (pytest, vitest) can graduate into their own modules behind the same interface.
include(":core")
