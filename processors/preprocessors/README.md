# Preprocessors

This directory contains request preprocessors for Todero.

## Source layout

```text
processors/
  preprocessors/
    auth-header-preprocessor/
```

## Runtime layout

```text
<workspace>/preprocessors/
  auth-header-preprocessor/
    auth-header-preprocessor.jar
```

## Build

```sh
mvn -f todero-monorepo-components/pom.xml   -pl processors/preprocessors/auth-header-preprocessor -am package
```
