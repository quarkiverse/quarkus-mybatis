# Quarkus MyBatis Extension
<!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->
[![All Contributors](https://img.shields.io/badge/all_contributors-2-orange.svg?style=flat-square)](#contributors-)
<!-- ALL-CONTRIBUTORS-BADGE:END -->

MyBatis is a first class persistence framework with support for custom SQL, stored procedures and advanced mappings. This extension provides the developers ease of configuration and native support. Add the following dependency in your pom.xml to get started,

```xml
<dependency>
    <groupId>io.quarkiverse.mybatis</groupId>
    <artifactId>quarkus-mybatis</artifactId>
</dependency>
```

And then your can use the ```@Mapper``` in your application just like

```java
@Mapper
public interface UserMapper {

    @Select("SELECT * FROM USERS WHERE id = #{id}")
    User getUser(Integer id);

    @Insert("INSERT INTO USERS (id, name) VALUES (#{id}, #{name})")
    Integer createUser(@Param("id") Integer id, @Param("name") String name);

    @Delete("DELETE FROM USERS WHERE id = #{id}")
    Integer removeUser(Integer id);
}
```

For more information and quickstart, you can check the complete [documentation](https://quarkiverse.github.io/quarkiverse-docs/quarkus-mybatis/dev/index.html).

## Contributors ✨

Thanks goes to these wonderful people ([emoji key](https://allcontributors.org/docs/en/emoji-key)):

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tr>
    <td align="center"><a href="https://github.com/zhfeng"><img src="https://avatars2.githubusercontent.com/u/1246139?v=4?s=100" width="100px;" alt=""/><br /><sub><b>Amos Feng</b></sub></a><br /><a href="https://github.com/quarkiverse/quarkus-mybatis/commits?author=zhfeng" title="Code">💻</a> <a href="#maintenance-zhfeng" title="Maintenance">🚧</a></td>
    <td align="center"><a href="https://github.com/362228416"><img src="https://avatars.githubusercontent.com/u/5248797?v=4?s=100" width="100px;" alt=""/><br /><sub><b>Chao</b></sub></a><br /><a href="https://github.com/quarkiverse/quarkus-mybatis/commits?author=362228416" title="Code">💻</a></td>
  </tr>
</table>

<!-- markdownlint-restore -->
<!-- prettier-ignore-end -->

<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!
