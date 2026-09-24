#!/bin/sh
# Generates the full Attack on Titan map into ./AttackOnTitan. Needs Java 17+.
cd "$(dirname "$0")" && java -Xmx4G -jar aot-world.jar generate AttackOnTitan "$@"
