![](https://avatars.githubusercontent.com/u/108533386?s=200&v=4)
# resource-packs (ci branch)

This branch holds the build tooling (Gradle wrapper, `buildSrc`, and the
`gradlew`-driven manifest/PR-validation tasks) used to publish the
`manifest` branch from the pack descriptors kept on `main`.

It intentionally does not contain any pack descriptors. The `main` branch
keeps pack descriptor files under `packs/` plus its own README aimed at
contributors; this branch is pulled in by the workflow on `main` purely to
run the Gradle tasks (`update-manifest`, `validate-manifest`,
`process-pr-comments`) against the descriptors checked out from `main`.

There's no reason to open PRs against pack submissions here — those go to
`main`. Changes to the tooling itself (buildSrc, workflow logic, Gradle
config) belong on this branch.
