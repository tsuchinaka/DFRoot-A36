#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef void (*kg_init)(void);
typedef void (*kg_term)(void);
typedef int (*kg_state)(int *);
typedef int (*kg_unlock)(int *);

int main(int argc, char **argv) {
    void *h = dlopen("/system/lib64/aidl_comm_kg_client.so", RTLD_NOW);
    if (h == NULL) {
        printf("dlopen=%s\n", dlerror());
        return 2;
    }
    kg_init init = (kg_init)dlsym(h, "_Z18TZ_KGTA_initializev");
    kg_term term = (kg_term)dlsym(h, "_Z17TZ_KGTA_terminatev");
    kg_state state = (kg_state)dlsym(h, "_Z18TZ_KGTA_getKgStatePi");
    kg_unlock unlock = (kg_unlock)dlsym(h, "_Z20TZ_KGTA_unlockScreenPi");
    if (init == NULL || state == NULL || unlock == NULL) {
        printf("dlsym failed init=%p state=%p unlock=%p\n", init, state, unlock);
        return 3;
    }
    init();
    int err = -1;
    int st = state(&err);
    printf("state=%d err=%d\n", st, err);
    if (argc > 1 && strcmp(argv[1], "unlock") == 0) {
        int uerr = -1;
        int r = unlock(&uerr);
        printf("unlock=%d err=%d\n", r, uerr);
    }
    if (term != NULL) term();
    dlclose(h);
    return 0;
}
