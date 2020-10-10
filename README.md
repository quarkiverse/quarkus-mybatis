# Welcome to Quarkiverse!
<!-- ALL-CONTRIBUTORS-BADGE:START - Do not remove or modify this section -->
[![All Contributors](https://img.shields.io/badge/all_contributors-1-orange.svg?style=flat-square)](#contributors-)
<!-- ALL-CONTRIBUTORS-BADGE:END -->

Congratulations and thank you for creating a new Quarkus extension project in Quarkiverse!

Feel free to replace this content with the proper description of your new project and necessary instructions how to use and contribute to it.

You can find the basic info, Quarkiverse policies and conventions in [the Quarkiverse wiki](https://github.com/quarkiverse/quarkiverse/wiki).

Need to quickly create a new Quarkus extension Maven project? Just execute the command below replacing the template values with your preferred ones:
```
mvn io.quarkus:quarkus-maven-plugin:<QUARKUS_VERSION>:create-extension -N \
    -DgroupId=io.quarkiverse.<REPO_NAME> \ 
    -DartifactId=<EXTENSION_ARTIFACT_ID> \  
    -Dversion=<INITIAL_VERSION> \ 
    -Dquarkus.nameBase="<EXTENSION_SIMPLE_NAME>"
```
**IMPORTANT:** make sure your project uses [io.quarkiverse:quarkiverse-parent](https://github.com/quarkiverse/quarkiverse-parent) as the parent POM. It will make sure the release and artifact publishing plugins are properly configured for your project.

In case you are creating a Quarkus extension project for the first time, please follow [Building My First Extension](https://quarkus.io/guides/building-my-first-extension) guide.

Other useful articles related to Quarkus extension development can be found under the [Writing Extensions](https://quarkus.io/guides/#writing-extensions) guide category on the [Quarkus.io](http://quarkus.io) website.

Thanks again, good luck and have fun!

## Contributors ✨

Thanks goes to these wonderful people ([emoji key](https://allcontributors.org/docs/en/emoji-key)):

<!-- ALL-CONTRIBUTORS-LIST:START - Do not remove or modify this section -->
<!-- prettier-ignore-start -->
<!-- markdownlint-disable -->
<table>
  <tr>
    <td align="center"><a href="https://github.com/zhfeng"><img src="https://avatars2.githubusercontent.com/u/1246139?v=4" width="100px;" alt=""/><br /><sub><b>Amos Feng</b></sub></a><br /><a href="https://github.com/quarkiverse/quarkiverse-mybatis/commits?author=zhfeng" title="Code">💻</a> <a href="#maintenance-zhfeng" title="Maintenance">🚧</a></td>
  </tr>
</table>

<!-- markdownlint-enable -->
<!-- prettier-ignore-end -->
<!-- ALL-CONTRIBUTORS-LIST:END -->

This project follows the [all-contributors](https://github.com/all-contributors/all-contributors) specification. Contributions of any kind welcome!