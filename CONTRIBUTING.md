# Contributing

Thanks for helping. Issues and pull requests are both welcome.

## Before you open a pull request

1. For a large change, open an issue first so we can agree on the approach.
2. Change the process models in `tools/generate_models.py`, then run `python3 tools/generate_models.py` and commit the regenerated files. Do not edit the BPMN, DMN or form files by hand: the next generator run overwrites them, and `BpmnModelsTest` checks that the variants stay in step.
3. Run the tests. They need Docker (Testcontainers):
   ```sh
   ./mvnw verify                  # Camunda 8.9 engine
   ./mvnw verify -Pcamunda-8.10   # Camunda 8.10 engine; runs the 8.10 models too
   ```
4. Keep the sample data fictional. No real bank, customer, cluster id, secret or internal URL in code, docs or screenshots.
5. Update the README when you change a setting, an endpoint or the setup steps.

## How pull requests are reviewed

The maintainer reviews pull requests within 30 days. A pull request is merged when the tests pass on both engines and the README matches the change.

## Code of Conduct

This project follows the [Camunda Community Hub Code of Conduct](https://github.com/camunda-community-hub/community/blob/main/docs/CODE_OF_CONDUCT.MD).
