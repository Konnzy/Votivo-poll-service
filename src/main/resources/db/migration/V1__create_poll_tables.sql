CREATE TABLE polls (
    id UUID PRIMARY KEY,
    version BIGINT NOT NULL DEFAULT 0,
    owner_id VARCHAR(255) NOT NULL,
    title VARCHAR(200) NOT NULL,
    description VARCHAR(5000) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED', 'ARCHIVED')),
    access_type VARCHAR(16) NOT NULL CHECK (access_type IN ('PUBLIC', 'LINK')),
    anonymous BOOLEAN NOT NULL,
    blocked BOOLEAN NOT NULL DEFAULT FALSE,
    share_token UUID NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    closed_at TIMESTAMP WITH TIME ZONE,
    archived_at TIMESTAMP WITH TIME ZONE
);

CREATE TABLE questions (
    id UUID PRIMARY KEY,
    poll_id UUID NOT NULL REFERENCES polls(id) ON DELETE CASCADE,
    text VARCHAR(2000) NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('SINGLE', 'MULTIPLE')),
    position INTEGER
);

CREATE TABLE answer_options (
    id UUID PRIMARY KEY,
    question_id UUID NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
    text VARCHAR(1000) NOT NULL,
    position INTEGER
);

CREATE INDEX idx_polls_owner_created ON polls(owner_id, created_at DESC, id DESC);
CREATE INDEX idx_polls_public ON polls(access_type, status, blocked, created_at DESC, id DESC);
CREATE INDEX idx_questions_poll ON questions(poll_id);
CREATE INDEX idx_options_question ON answer_options(question_id);
