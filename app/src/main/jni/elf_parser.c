#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <linux/elf.h>

#define MAX_PHNUM 1024
#define MAX_SHNUM 4096

static int read_full(int fd, void *buf, size_t len, off_t off) {
    unsigned char *p = (unsigned char *)buf;
    size_t done = 0;
    while (done < len) {
        ssize_t n = pread(fd, p + done, len - done, off + (off_t)done);
        if (n <= 0) return -1;
        done += (size_t)n;
    }
    return 0;
}

static void read_name(int fd, off_t off, off_t limit, char *out, size_t cap) {
    size_t want = cap - 1;
    if (off < 0 || off >= limit) {
        out[0] = '\0';
        return;
    }
    if ((off_t)want > limit - off) {
        want = (size_t)(limit - off);
    }
    if (want == 0 || read_full(fd, out, want, off) != 0) {
        out[0] = '\0';
        return;
    }
    out[want] = '\0';
    out[cap - 1] = '\0';
}

int find_hook_target(const char *lib, const char *symname, uint64_t *hook_target,
                     uint64_t *payload_target, uint32_t *first_instruction) {
    Elf64_Ehdr hdr;
    *hook_target = 0;
    *payload_target = 0;

    int fd = open(lib, O_RDONLY);
    if (fd < 0) {
        fprintf(stderr, "elf_parser: open %s failed\n", lib);
        return 1;
    }

    struct stat st;
    if (fstat(fd, &st) != 0 || st.st_size < (off_t)sizeof(hdr)) {
        fprintf(stderr, "elf_parser: %s too small\n", lib);
        close(fd);
        return 1;
    }
    off_t file_size = st.st_size;
    uint64_t fsize = (uint64_t)file_size;

    if (read_full(fd, &hdr, sizeof(hdr), 0) != 0) {
        fprintf(stderr, "elf_parser: read header %s failed\n", lib);
        close(fd);
        return 1;
    }
    if (memcmp(hdr.e_ident, "\x7f" "ELF", 4) != 0 || hdr.e_ident[EI_CLASS] != ELFCLASS64) {
        fprintf(stderr, "elf_parser: %s is not ELF64\n", lib);
        close(fd);
        return 1;
    }
    if (hdr.e_phentsize != sizeof(Elf64_Phdr) || hdr.e_phnum == 0
            || hdr.e_phnum > MAX_PHNUM
            || hdr.e_phoff > fsize) {
        fprintf(stderr, "elf_parser: %s bad program headers\n", lib);
        close(fd);
        return 1;
    }
    if (hdr.e_shentsize != sizeof(Elf64_Shdr) || hdr.e_shnum == 0
            || hdr.e_shnum > MAX_SHNUM || hdr.e_shstrndx >= hdr.e_shnum
            || hdr.e_shoff > fsize) {
        fprintf(stderr, "elf_parser: %s bad section headers\n", lib);
        close(fd);
        return 1;
    }

    uint64_t executable_off = 0;
    uint64_t executable_vaddr = 0;
    for (int i = 0; i < hdr.e_phnum; i++) {
        Elf64_Phdr phdr;
        uint64_t off = hdr.e_phoff + (uint64_t)i * (uint64_t)sizeof(phdr);
        if (off > fsize || fsize - off < sizeof(phdr)
                || read_full(fd, &phdr, sizeof(phdr), (off_t)off) != 0) {
            fprintf(stderr, "elf_parser: %s program header %d unreadable\n", lib, i);
            close(fd);
            return 1;
        }
        if (phdr.p_type == PT_LOAD && (phdr.p_flags & PF_X)) {
            *payload_target = phdr.p_offset + phdr.p_filesz;
            executable_off = phdr.p_offset;
            executable_vaddr = phdr.p_vaddr;
            break;
        }
    }
    if (executable_off == 0) {
        fprintf(stderr, "elf_parser: %s has no executable PT_LOAD\n", lib);
        close(fd);
        return 1;
    }

    Elf64_Shdr str_shdr;
    uint64_t str_off = hdr.e_shoff + (uint64_t)hdr.e_shstrndx * (uint64_t)sizeof(Elf64_Shdr);
    if (str_off > fsize || fsize - str_off < sizeof(str_shdr)
            || read_full(fd, &str_shdr, sizeof(str_shdr), (off_t)str_off) != 0) {
        fprintf(stderr, "elf_parser: %s shstrtab header unreadable\n", lib);
        close(fd);
        return 1;
    }

    uint64_t dynstr = 0;
    uint64_t dynsym_offset = 0;
    uint64_t dynsym_size = 0;
    for (int i = 0; i < hdr.e_shnum; i++) {
        Elf64_Shdr shdr;
        uint64_t off = hdr.e_shoff + (uint64_t)i * (uint64_t)sizeof(Elf64_Shdr);
        if (off > fsize || fsize - off < sizeof(shdr)
                || read_full(fd, &shdr, sizeof(shdr), (off_t)off) != 0) {
            fprintf(stderr, "elf_parser: %s section %d unreadable\n", lib, i);
            close(fd);
            return 1;
        }
        char name[100];
        read_name(fd, (off_t)(str_shdr.sh_offset + shdr.sh_name), file_size, name, sizeof(name));
        if (strcmp(name, ".dynstr") == 0) {
            dynstr = shdr.sh_offset;
        } else if (strcmp(name, ".dynsym") == 0) {
            dynsym_offset = shdr.sh_offset;
            dynsym_size = shdr.sh_size;
        }
    }

    if (dynstr == 0 || dynsym_offset == 0 || dynsym_size == 0
            || dynstr > (uint64_t)file_size || dynsym_offset > (uint64_t)file_size
            || dynsym_offset + dynsym_size > (uint64_t)file_size) {
        fprintf(stderr, "elf_parser: %s .dynstr/.dynsym missing or out of range\n", lib);
        close(fd);
        return 1;
    }

    size_t symbols = (size_t)(dynsym_size / sizeof(Elf64_Sym));
    for (size_t i = 0; i < symbols; i++) {
        Elf64_Sym sym;
        uint64_t off = dynsym_offset + (uint64_t)i * (uint64_t)sizeof(Elf64_Sym);
        if (off > fsize || fsize - off < sizeof(sym)
                || read_full(fd, &sym, sizeof(sym), (off_t)off) != 0) {
            fprintf(stderr, "elf_parser: %s dynsym %zu unreadable\n", lib, i);
            close(fd);
            return 1;
        }
        char name[200];
        read_name(fd, (off_t)(dynstr + sym.st_name), file_size, name, sizeof(name));
        if (strcmp(name, symname) == 0) {
            if (sym.st_value < executable_vaddr) {
                fprintf(stderr, "elf_parser: %s symbol %s outside executable segment\n",
                        lib, symname);
                close(fd);
                return 1;
            }
            *hook_target = sym.st_value - executable_vaddr + executable_off;
        }
    }
    if (*hook_target == 0) {
        fprintf(stderr, "elf_parser: %s symbol %s not found\n", lib, symname);
        close(fd);
        return 1;
    }
    if (*hook_target + 4 > fsize) {
        fprintf(stderr, "elf_parser: %s hook target out of range\n", lib);
        close(fd);
        return 1;
    }
    if (read_full(fd, first_instruction, sizeof(*first_instruction), (off_t)*hook_target) != 0) {
        fprintf(stderr, "elf_parser: %s first instruction unreadable\n", lib);
        close(fd);
        return 1;
    }
    if (*first_instruction == 0xd503233fU || *first_instruction == 0xd503245fU) {
        /* PACIASP or BTI: hook the next instruction. */
        *hook_target += 4UL;
        if (*hook_target + 4 > fsize
                || read_full(fd, first_instruction, sizeof(*first_instruction),
                             (off_t)*hook_target) != 0) {
            fprintf(stderr, "elf_parser: %s next instruction unreadable\n", lib);
            close(fd);
            return 1;
        }
    }

    close(fd);
    return 0;
}
