DROP TABLE USERS IF EXISTS;
DROP TABLE BOOKS IF EXISTS;

CREATE TABLE USERS (
    id integer not null primary key,
    name varchar(80) not null,
    externalId uuid not null
);

CREATE TABLE BOOKS (
    id integer not null primary key,
    title varchar(80) not null,
    author_id integer not null,

    foreign key(author_id) references USERS(id)
);