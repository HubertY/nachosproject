#include "stdio.h"
#include "stdlib.h"


int test(int value, int expected){
    if(value == expected){
        printf("passed\n");
    }
    else{
        printf("failed; got %d\n", value);
        exit(-1);
    }
}

int
main ()
{
    char* s = "hello world\n";
    char* badString = 0xBADFFF;

    printf("testing stdout\n");
    test(write(1, s, strlen(s)), strlen(s));

    printf("testing file creat\n");
    int descriptor = creat("file.out");
    test((descriptor >=2 && descriptor <= 16), 1);

    printf("testing file creat with bad string\n");
    test(creat(badString), -1);

    printf("testing duplicate file open\n");
    int descriptor2 = open("file.out");
    test((descriptor2 >=2 && descriptor2 <= 16 && descriptor2 != descriptor), 1);

    printf("testing nonexistent file open\n");
    test(open("nonexistent.out"), -1);

    printf("testing file close\n");
    test(close(descriptor2), 0);

    printf("testing unopened file close\n");
    test(close(descriptor2), -1);

    printf("testing file close with bad descriptor\n");
    test(close(-1), -1);

    printf("testing file write length 0\n");
    test(write(descriptor, s, 0), 0);

    printf("testing file write\n");
    test(write(descriptor, s, strlen(s)), strlen(s));

    printf("testing file write partial string\n");
    test(write(descriptor, s, 3), 3);

    printf("testing file write with bad write length\n");
    test(write(descriptor, s, -1), -1);

    printf("testing file write with bad string\n");
    test(write(descriptor, badString, 10), -1);

    printf("testing file write to unopened file\n");
    test(write(descriptor2, s, strlen(s)), -1);

    printf("testing file read\n");
    close(descriptor);
    descriptor = open("file.out");
    char bufc[100];
    test(read(descriptor, bufc, 100), strlen(s) + 3);
    printf("%s\n", bufc);

    printf("testing file read with bad descriptor\n");
    test(read(-1, bufc, 100), -1);

    printf("testing file read from unopened file\n");
    test(read(descriptor2, bufc, 100), -1);

    printf("testing file read with bad read length\n");
    test(read(descriptor, bufc, -1), -1);

    printf("testing unlink\n");
    test(unlink("file.out"), 0);

    printf("testing double unlink\n");
    test(unlink("file.out"), -1);

    printf("testing unlink with bad string\n");
    test(unlink(badString), -1);

    char* argv[3];
    char* a = "a";
    char* b = "b";
    char* c = "c";
    argv[0] = a;
    argv[1] = b;
    argv[2] = c;

    printf("testing exec, halt does not halt\n");
    exec("halt.coff", 3, argv);
    test(1,1);

    int buf[4];

    printf("testing exec with nonexistent file\n");
    test(exec("nonexistent.coff", 3, argv), -1);

    printf("testing exec with noncoff file\n");
    test(exec("halt.c", 3, argv), -1);

    printf("testing join\n");
    int id = exec("matmult.coff", 3, argv);
    join(id, buf);
    printf("joined\n");

    printf("testing join\n");
     id = exec("matmult.coff", 3, argv);
    join(id, buf);
    printf("joined\n");

    printf("testing join\n");
     id = exec("matmult.coff", 3, argv);
    join(id, buf);
    printf("joined\n");

    test(1,1);

    return 0;
}

