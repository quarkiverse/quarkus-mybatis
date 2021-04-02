CREATE TABLE USERS (
    id integer not null primary key,
    name varchar(80) not null,
    externalId uuid not null
);

DELETE FROM users;
insert into users (id, name, externalId) values (1, 'Test User1', '8c5034fe-1a00-43b7-9c75-f83ef14e3507');
insert into users (id, name, externalId) values (2, 'Test User2', '0a197020-e05a-41ab-9c46-649cd432feb4');
insert into users (id, name, externalId) values (3, 'Test User3', '9b54c1b1-5e7d-4a64-a06c-9a5b9531e2ee');





