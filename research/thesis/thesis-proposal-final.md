# What Slows You Down: Classifying Delay Sources on Kathmandu's Urban Corridors Using Real-Time GPS and Accelerometer Observations

---

## Research Questions

**RQ1:** What is the composition of travel delay by cause on three selected urban corridors in Kathmandu, and how does this composition vary by corridor and time of day?

**RQ2:** After controlling for intersection proximity, bus stop density, and market presence, does segment-level road surface roughness --- measured via smartphone accelerometer --- independently predict the frequency of vehicle slowdowns?

---

## Objectives

**Objective 1:** To detect, classify, and rank the causes of vehicle delay events on three urban corridors using GPS speed profiles, accelerometer data, and real-time observer annotation from a moving vehicle.

**Objective 2:** To determine whether road surface roughness is a statistically significant independent predictor of segment-level slowdown frequency after accounting for other corridor features.

---

## Methodology

**Platform:** Researcher's own vehicle, driven along urban corridors at morning peak, evening peak, and off-peak periods.

**Data collected per trip:** GPS trace (1 Hz), accelerometer trace (50 Hz, Z-axis), voice-annotated cause codes at each slowdown, infrastructure checklist per segment.

**Delay event:** GPS speed below 5 km/hr sustained for more than 20 seconds.

**Eight cause categories:**

| Code | Category | How Detected |
|------|----------|-------------|
| **S** | Signal delay | Visual + known intersection location |
| **Q** | Unsignalized intersection queue | Visual |
| **B** | Bus / PT vehicle interference | Visual (bus stopped for boarding) |
| **P** | Pedestrian crossing | Visual |
| **R** | Road surface roughness | Visual + accelerometer Z-axis spike |
| **F** | Side friction | Visual (parking maneuver, loading, rickshaw) |
| **T** | Turning vehicle | Visual (U-turn, side road entry) |
| **E** | Market / encroachment | Visual (vendor stalls on carriageway) |

**Sampling:** 3 corridors × 3 time bands × 5 rides = 45 trips. Each corridor divided into 50-meter segments, observed ~15 times across all passes.

**Roughness:** RMS of Z-axis accelerometer variance per 50m segment, averaged across passes, validated against visual observations.

**Analysis:**

| Question | Method |
|----------|--------|
| RQ1 | Descriptive: ranked cause lists, GIS hotspots |
| RQ2 | Negative binomial mixed-effects regression: slowdown count ~ roughness + signal distance + bus stop density + market presence + log(offset); random intercept per segment |
| Reliability | Intra-rater: 10% of trips video-recorded and re-coded by same observer after 2-week gap; Cohen's kappa between live and replay annotation |
| Power | Monte Carlo simulation, minimum detectable effect at 80% |

**Equipment:** One smartphone.

---

## Expected Outcomes

1. Per-corridor ranked delay composition --- proportion of stopped time by each cause per corridor and time band.

2. GIS hotspot map of slowdown events, color-coded by dominant cause per 50m segment.

3. Regression results: effect size, confidence interval, and significance of roughness as a delay predictor after controls.

4. First smartphone-derived urban roughness dataset for Kathmandu corridors.

5. Per-corridor intervention recommendations matched to dominant cause: signal coordination, bus stop redesign, road resurfacing, or enforcement.
