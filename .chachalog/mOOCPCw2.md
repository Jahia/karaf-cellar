---
# Allowed version bumps: patch, minor, major
karaf-cellar: patch
---

Fixed clustered installs so a configuration a node receives from the cluster is the same configuration on every node. Separate copies on each node were what let a restart revert a module's settings to an earlier version.
