CREATE TABLE runtime_control_events (
    id UUID PRIMARY KEY,
    paused BOOLEAN NOT NULL,
    actor VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX runtime_control_events_created_at_idx
    ON runtime_control_events (created_at DESC);
