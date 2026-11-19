#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef void (*kg_init)(void);
typedef void (*kg_term)(void);
typedef int (*kg_state)(int *);
typedef int (*kg_unlock)(int *);

/*
 * One invocation reports pre state, the unlock attempt (only in Active(2) /
 * Locked(3)) and post state. The app parses key=value lines; a state outside
 * 2/3 is never unlocked so a Prenormal/Checking device cannot be forced into
 * an invalid TA transition.
 */
int main(int argc, char **argv) {
    int unlock_mode = (argc > 1 && strcmp(argv[1], "unlock") == 0);

    void *h = dlopen("/system/lib64/aidl_comm_kg_client.so", RTLD_NOW);
    if (h == NULL) {
        printf("dlopen_error=%s\n", dlerror());
        return 2;
    }
    kg_init init = (kg_init)dlsym(h, "_Z18TZ_KGTA_initializev");
    kg_term term = (kg_term)dlsym(h, "_Z17TZ_KGTA_terminatev");
    kg_state state = (kg_state)dlsym(h, "_Z18TZ_KGTA_getKgStatePi");
    kg_unlock unlock = (kg_unlock)dlsym(h, "_Z20TZ_KGTA_unlockScreenPi");
    if (state == NULL || unlock == NULL) {
        printf("dlsym_error=1 state=%p unlock=%p\n", (void *)state, (void *)unlock);
        dlclose(h);
        return 3;
    }
    if (init != NULL) {
        init();
    }

    int pre_err = -1;
    int pre = state(&pre_err);
    printf("pre_state=%d pre_err=%d\n", pre, pre_err);

    int rc = -1;
    int uerr = 0;
    int skipped = 0;
    if (unlock_mode) {
        if (pre == 2 || pre == 3) {
            uerr = -1;
            rc = unlock(&uerr);
        } else {
            skipped = 1;
        }
    }
    printf("unlock=%d unlock_err=%d skipped=%d\n", rc, uerr, skipped);

    int post_err = -1;
    int post = state(&post_err);
    printf("post_state=%d post_err=%d\n", post, post_err);

    if (term != NULL) {
        term();
    }
    dlclose(h);
    return 0;
}
