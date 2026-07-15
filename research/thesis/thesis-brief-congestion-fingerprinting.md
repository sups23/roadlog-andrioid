# Thesis Brief

## Title

**"What Slows You Down: Real-Time GPS Classification of Delay Sources on Kathmandu's Urban Corridors"**

---

## Problem Statement

Travel delays on Kathmandu's roads are severe and universally acknowledged. Buses average under 10 km/hr. Intersections can take over 7 minutes to cross. But existing research answers only **how much delay occurs** — not **what specifically causes a vehicle to stop at any given location.**

Paper 7 (2023) modeled seven delay types on a single 5.6 km route during morning hours only. It found that undefined curb stops generated the highest mean delay (2.18 minutes) yet were statistically insignificant in the predictive model — a paradox that remains unexplained, likely due to missing spatial context. Paper 1 measured queue lengths at three intersections but never asked whether those queues were caused by signal timing, pedestrian interference, roadside friction, or PT vehicle behavior.

This knowledge gap persists because Kathmandu has no network of fixed traffic sensors. Interventions are consequently generic: widen roads, add signals, enforce parking — without evidence that the targeted cause is actually the dominant source of delay on the corridor in question.

**This research closes the gap by classifying the real-time causes of individual stop events — signal timing, unsignalized queuing, bus interference, pedestrian crossings, side friction, road defects, turning vehicles, and market encroachment — using nothing but a smartphone GPS trace and structured observation from a moving ride-share vehicle.**

---

## Research Questions

**Primary:**

**RQ1:** What is the composition of travel delay by cause on three selected urban corridors in Kathmandu, and how does this composition vary by corridor and time of day?

**Secondary:**

**RQ2:** Which individual bottleneck locations cause the highest total delay (frequency × duration) on each corridor, and what is their dominant cause?

**RQ3:** What proportion of total journey time is spent in a stopped state (speed below 5 km/hr), and what proportion of that stopped time is attributable to each cause category?

**RQ4:** Are specific delay causes spatially clustered — for example, bus interference concentrated near designated stops, market friction near commercial nodes, pedestrian crossing near hospitals and schools — or randomly distributed?

**RQ5:** Can a voice-annotated smartphone GPS observation protocol from a moving ride-share vehicle produce a classified delay-source map with sufficient reliability to guide corridor-level intervention decisions?

---

## Methodology Summary

**Observation platform:** Ride-share vehicle (Pathao/Tootle/inDrive) — car and/or motorcycle — traveling along study corridors during peak and off-peak hours.

**Data collection per ride:**
- Continuous GPS trace (1 Hz: lat, lon, speed, timestamp) via smartphone logger
- Voice-annotated cause codes spoken in real time at each stop event
- Structured observation checklist for infrastructure conditions
- Ride receipt (fare, distance, duration) for cost-per-minute-saved context

**Delay event definition:** GPS speed drops below 5 km/hr continuously for more than 20 seconds.

**Classification framework (10 cause codes):**

| Code | Category | Real-World Example |
|------|----------|-------------------|
| S | Signal delay | Red light at Balaju Chowk |
| Q | Queue at unsignalized intersection | Sorakhutte Chowk at 9 AM |
| B | Bus/PT vehicle interference | Micro stopped mid-lane to pick up passengers |
| P | Pedestrian crossing | People crossing near a hospital, with or without zebra |
| F | Side friction | Double-parked car, loading rickshaw, vendor spillover |
| R | Road condition | Pothole cluster, waterlogging, broken surface |
| T | Turning vehicle | U-turn at mid-block, vehicle entering from side road |
| V | General volume | Too many vehicles; no single trigger visible |
| M | Market/encroachment | Street stalls, informal vendors reducing usable width |
| X | Unexplained | No identifiable cause |

**Sampling:** 3 corridors × 3 time bands (AM peak, PM peak, off-peak) × 3 rides each = ~27 rides. Plus 3 weekend rides for comparison. Total: ~30 observed trips, producing approximately 500–1000 classified stop events.

**Analysis:** Delay decomposition per corridor-time-direction. Spatial hotspot mapping in QGIS. Severity ranking of individual bottlenecks. Chi-square tests for cause distribution differences across corridors.

---

## Scope Boundaries

| Included | Excluded |
|----------|----------|
| 3 corridors with contrasting characteristics | City-wide mapping |
| Cause classification of stop events | Automated causation detection |
| AM peak, PM peak, off-peak | Night-time data |
| Ride-share vehicle as observation platform | Public transport passenger data |
| Weekday + weekend comparison | Seasonal analysis |
| Descriptive statistics + spatial analysis | Predictive or simulation modeling |
| Per-corridor policy recommendations | Cost-benefit analysis |

---

## Expected Output

A **classified delay-source map** for each corridor, enabling the kind of targeted recommendation that existing research cannot produce:

> *"On the Banasthali–Sundhara corridor during morning peak, 41% of all stopped time is caused by signal delay and unsignalized queuing at just two intersections (Balaju and Sorakhutte). These should be prioritized for signal coordination. On the Lagankhel–Jamal corridor during evening peak, side friction and market encroachment account for 47% of delay — enforcement and vendor relocation would yield greater improvement than road widening."*

---

## Equipment

One smartphone. Zero additional cost.
