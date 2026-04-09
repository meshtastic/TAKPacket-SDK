# TAKPacket-SDK Compression Report
Generated: 2026-04-09 | Dictionary: v1 (non-aircraft 8KB + aircraft 4KB)

## Summary
| Metric | Value |
|--------|-------|
| Total test messages | 9 |
| 100% under 237B | YES |
| Median compressed size | 109B |
| Median compression ratio | 6.4x |
| Worst case | 160B (67% of LoRa MTU) |

## Per-Message Results
| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |
|---------|----------|----------|------------|------------|-------|------|
| pli_basic | a-f-G-U-C | 446B | 64B | 63B | 7.1x | non-aircraft |
| pli_full | a-f-G-U-C | 750B | 189B | 160B | 4.7x | non-aircraft |
| pli_webtak | a-f-G-U-C-I | 665B | 138B | 147B | 4.5x | non-aircraft |
| geochat_simple | b-t-f | 829B | 138B | 130B | 6.4x | non-aircraft |
| aircraft_adsb | a-n-A-C-F | 715B | 102B | 109B | 6.6x | aircraft |
| aircraft_hostile | a-h-A-M-F-F | 623B | 114B | 115B | 5.4x | aircraft |
| delete_event | t-x-d-d | 476B | 48B | 62B | 7.7x | non-aircraft |
| casevac | b-r-f-h-c | 594B | 53B | 67B | 8.9x | non-aircraft |
| alert_tic | b-a-o-opn | 406B | 49B | 63B | 6.4x | non-aircraft |

## Size Distribution
```
delete_event           62B |#############
pli_basic              63B |#############
alert_tic              63B |#############
casevac                67B |##############
aircraft_adsb         109B |######################
aircraft_hostile      115B |########################
geochat_simple        130B |###########################
pli_webtak            147B |###############################
pli_full              160B |#################################
LoRa MTU              237B |##################################################
```
