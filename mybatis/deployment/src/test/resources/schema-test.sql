CREATE TABLE USERS (
    id integer not null primary key,
    name varchar(80) not null,
    externalId uuid not null
);