---
# Allowed version bumps: patch, minor, major
karaf-cellar: patch
---

Fixed clustered installs so deleting a configuration that no file feeds no longer reports an error for a deletion that succeeded, and no longer stops a node from cleaning up its remaining configurations at startup.
