![](https://avatars.githubusercontent.com/u/108533386?s=200&v=4)
# resource-packs

This repository contains officially recognized resource packs for 117 HD.

## Creating new Resource Pack
There are two methods to create an external pack, you can either:

- Use [this](https://github.com/Mark7625/resource-packs/generate) pack template.
- Make a new public repo and add your files to your git repo and push the project.

## Development

[How to design a pack](https://github.com/117HD/RLHD/wiki/v1.1.2-Update-Changelog#environments)


## Submitting a resource pack
1. Fork the [resource-packs repository](https://github.com/117HD/resource-packs).
2. Create a new branch for your pack.
3. Create a new file directly in the `packs` directory with the fields:
 ```
internalName=
repository=
commit=
 ```
   `internalName` is the stable identifier used by the client. It must be unique and contain only lowercase letters, numbers, `_`, and `-`. It is not the pack's display name.

4. To get the repository URL, copy the exact repository URL from the address bar. It must be in the form `https://github.com/owner/repository`, without a trailing slash or any additional path.

5. To get the commit hash, go to your pack repository on GitHub and click on commits. Choose the latest one and copy the full 40-character hash. It can be seen in the top right after selecting a commit. Paste this into the `commit=` field of the file.
   Your file should now look something like this:
 ```
internalName=my_resource_pack
repository=https://github.com/Mark7625/resource-packs
commit=9db374fc205c5aae1f99bd5fd127266076f40ec8
 ```
6. This is the only change you need to make, so commit your changes and push them to your fork. Then go back to the [resource-packs repository](https://github.com/117HD/resource-packs) and click *New pull request* in the upper left. Choose *Compare across forks* and select your fork and branch as head and compare.

7. Write a short description of what your pack does and then create your pull request.

8. Be patient and wait for your pack to be reviewed and merged.

## Updating a pack

To update a pack, change its descriptor's `commit` to the most recent full 40-character Git commit hash. Do not change `internalName` when updating an existing pack.
