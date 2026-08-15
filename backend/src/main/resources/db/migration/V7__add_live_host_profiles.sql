CREATE TABLE live_host_profiles (
    room_id VARCHAR(64) PRIMARY KEY,
    host_name VARCHAR(80) NOT NULL,
    persona VARCHAR(1000) NOT NULL,
    live_topic VARCHAR(500) NOT NULL,
    reply_style VARCHAR(500) NOT NULL,
    max_reply_characters INTEGER NOT NULL,
    forbidden_topics VARCHAR(1000) NOT NULL,
    welcome_message VARCHAR(500) NOT NULL,
    proactive_questions BOOLEAN NOT NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE reply_candidates
    ADD COLUMN prompt_profile_version BIGINT;
