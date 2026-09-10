---
# Allowed version bumps: patch, minor, major
karaf-cellar: patch
---

Fixed clustered installs so a module configuration is no longer reverted to an earlier version when a node starts up. When the cluster holds several descriptions of the same configuration file and they do not agree, a starting node now keeps the file it has instead of applying one of them at random, and it republishes that file.
