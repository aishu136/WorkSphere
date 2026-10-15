// Time-bound allocations: (:Employee)-[:WORKS_ON {startDate, endDate}]->(:Project). endDate is optional
// (open-ended). Assignments made before dates existed start on the day the person joined.
MATCH ()-[w:WORKS_ON]->() WHERE w.startDate IS NULL SET w.startDate = coalesce(w.since, date());
