## Smithy Go

Smithy code generators for Go.

**WARNING: All interfaces are subject to change.**

## Setup

1. Install Java 11. If you have multiple versions of Java installed on OSX, use `export JAVA_HOME=`/usr/libexec/java_home -v 11``. **Java 14 is not compatible with Grade 5.x**
2. Install Go (follow directions for your platform)
3. Use `./gradlew` to automatically install the correct gradle version. **`brew install gradle` will install Gradle 6.x which is not compatible.**
4. `./gradlew test` to run the basic tests.
5. `cd smithy-go-codegen-test; ../gradlew build` to run the codegen tests

## License

This project is licensed under the Apache-2.0 License.

