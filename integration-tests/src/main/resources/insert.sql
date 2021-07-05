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

DELETE FROM users;
insert into users (id, name, externalId) values (1, 'Test User1', 'ccb16b65-8924-4c3f-8c55-681d85a16e79');
insert into users (id, name, externalId) values (2, 'Test User2', 'ae43f233-0b69-4c4e-bfa9-656c475150ad');
insert into users (id, name, externalId) values (3, 'Test User3', '5640e179-466c-427e-9747-4cfac09a2f9a');

DELETE from books;
insert into books(id, title, author_id) values (1, 'Test Title', 1);
