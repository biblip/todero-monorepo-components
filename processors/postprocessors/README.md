# Postprocessors

This directory contains response postprocessors for Todero.

## Source layout

```text
processors/
  postprocessors/
    response-header-postprocessor/
```

## Runtime layout

```text
<workspace>/postprocessors/
  response-header-postprocessor/
    response-header-postprocessor.jar
```

## Build

```sh
mvn -f todero-monorepo-components/pom.xml   -pl processors/postprocessors/response-header-postprocessor -am package
```
