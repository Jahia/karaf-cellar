---
# Allowed version bumps: patch, minor, major
karaf-cellar: patch
---

Fixed clustered installs so a configuration deleted on one node reaches the other nodes even when no file feeds it. A configuration changed or removed in the cluster while a node is synchronising no longer stops that node from applying the rest of its configurations, and is no longer reverted across the cluster by the node that was synchronising.
