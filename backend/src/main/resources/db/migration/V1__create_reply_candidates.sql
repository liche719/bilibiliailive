CREATE TABLE reply_candidates (
    id UUID PRIMARY KEY,
    room_id VARCHAR(64) NOT NULL,
    source_text VARCHAR(512) NOT NULL,
    candidate_text VARCHAR(512),
    status VARCHAR(32) NOT NULL,
    decision_reason VARCHAR(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX reply_candidates_status_created_at_idx
    ON reply_candidates (status, created_at DESC);
