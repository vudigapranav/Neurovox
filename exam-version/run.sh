#!/bin/sh
cd "$(dirname "$0")"
javac Main.java audio/*.java analysis/*.java && java Main
