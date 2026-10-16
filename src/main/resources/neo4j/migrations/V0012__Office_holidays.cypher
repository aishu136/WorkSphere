// Public holidays per office: (:Office)-[:OBSERVES]->(:Holiday {id, date, name}).
CREATE CONSTRAINT holiday_id IF NOT EXISTS FOR (h:Holiday) REQUIRE h.id IS UNIQUE;
CREATE INDEX holiday_date IF NOT EXISTS FOR (h:Holiday) ON (h.date);
