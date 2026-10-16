// Refresh tokens: only a hash of each token is stored.
CREATE CONSTRAINT refresh_token_id IF NOT EXISTS FOR (t:RefreshToken) REQUIRE t.id IS UNIQUE;
CREATE CONSTRAINT refresh_token_hash IF NOT EXISTS FOR (t:RefreshToken) REQUIRE t.tokenHash IS UNIQUE;
CREATE INDEX refresh_token_username IF NOT EXISTS FOR (t:RefreshToken) ON (t.username);
