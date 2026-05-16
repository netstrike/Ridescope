Import("env")

from os.path import join


def _patch_riscv_toolchain_path():
    package_dir = env.PioPlatform().get_package_dir("toolchain-riscv32-esp")
    if not package_dir:
        return

    nested_bin_dir = join(package_dir, "riscv32-esp-elf", "bin")
    env.PrependENVPath("PATH", nested_bin_dir)


_patch_riscv_toolchain_path()
