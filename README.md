![](https://avatars.githubusercontent.com/u/108533386?s=200&v=4)
# resource-packs

This repository contains the packs for 117HD these packs are not officially supported by the 177HD Team.

## Creating new Resource Pack
There are two methods to create an external plugin, you can either:

- Use [this](https://github.com/Mark7625/resource-packs/generate) pack template.
- Make a new Public repo and add your files to the dir and push the project.

## Development

[How to design a pack](https://github.com/117HD/RLHD/wiki/v1.1.2-Update-Changelog#environments)


## Submitting a resource pack
1. Fork the [resource-packs repository](https://github.com/117HD/resource-packs).
2. Create a new branch for your plugin.
3. Create a new file in the `packs` directory with the fields:
 ```
repository=
commit=
 ```
4. To get the repository url, copy the url from the address bar. Paste the url in in the `repository=` field.

5. To get the commit hash, go to your plugin repository on GitHub and click on commits. Choose the latest one and copy the full 40-character hash. It can be seen in the top right after selecting a commit. Paste this into the `commit=` field of the file.
   Your file should now look something like this:
 ```
repository=https://github.com/Mark7625/resource-packs/
commit=9db374fc205c5aae1f99bd5fd127266076f40ec8
 ```
6. This is the only change you need to make, so commit your changes and push them to your fork. Then go back to the [resource-packs repository](https://github.com/117HD/resource-packs) and click *New pull request* in the upper left. Choose *Compare across forks* and select your fork and branch as head and compare.

7. Write a short description of what your plugin does and then create your pull request.

8. Be patient and wait for your plugin to be reviewed and merged.

## Updating a plugin
To update a plugin, simply update the manifest with the most recent commit hash.

## Plugin resources
Resources may be included with plugins, which are non-code and are bundled and distributed with the plugin, such as images and sounds. You may do this by placing them in `src/main/resources`. Plugins on the pluginhub are distributed in .jar form and the jars placed into the classpath. The plugin is not unpacked on disk, and you can not assume that it is. This means that using https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html#getResource-java.lang.String- will return a jar-URL when the plugin is deployed to the pluginhub, but in your IDE will be a file-URL. This almost certainly makes it behave differently from how you expect it to, and isn't what you want.
Instead, prefer using https://docs.oracle.com/javase/8/docs/api/java/lang/Class.html#getResourceAsStream-java.lang.String-.
