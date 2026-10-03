# Child recovery ordering

Review hypothesis, not confirmed: READY listeners can recover a parent before child LOST scanning. Inspect actual production call sites and construct a forced interleaving. If reachable, order recovery or restrict scan to the previous process epoch; do not add a child worker or assume a local lease is distributed. Assert committed parent output acknowledgement still follows parent success.
