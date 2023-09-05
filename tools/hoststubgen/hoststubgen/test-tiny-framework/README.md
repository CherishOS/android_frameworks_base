# HostStubGen: tiny-framework test

This directory contains a small classes that "simulates" framework.jar, and tests against it.

This test doesn't use the actual android framework code.

## How to run

- With `atest`. This is the proper way to run it, but `atest` has known problems that may
  affect the result. If you see weird problems, try the next `run-ravenwood-test` command.

```
$ atest hoststubgen-test-tiny-test
```

- With `run-ravenwood-test` should work too. This is the proper way to run it.

```
$ run-ravenwood-test hoststubgen-test-tiny-test
```

- `run-test-manually.sh` also run the test, but it builds the stub/impl jars and the test without
  using the build system. This is useful for debugging the tool.

```
$ ./run-test-manually.sh
```