package nachos.userprog;

import nachos.machine.*;
import nachos.threads.*;
import nachos.userprog.*;

import java.io.EOFException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;

/**
 * Encapsulates the state of a user process that is not contained in its user
 * thread (or threads). This includes its address translation state, a file
 * table, and information about the program being executed.
 *
 * <p>
 * This class is extended by other classes to support additional functionality
 * (such as additional syscalls).
 *
 * @see nachos.vm.VMProcess
 * @see nachos.network.NetProcess
 */
public class UserProcess {
    private static class AtomicCounter {
        public AtomicCounter(int i) {
            value = i;
            lock = new Lock();
        }

        public int plusplus() {
            lock.acquire();
            int ret = value++;
            lock.release();
            return ret;
        }

        public int minusminus() {
            lock.acquire();
            int ret = value--;
            lock.release();
            return ret;
        }

        private Lock lock;
        private int value;
    }

    /**
     * Allocate a new process.
     */
    public UserProcess() {
        processCounter.plusplus();

        int numPhysPages = Machine.processor().getNumPhysPages();
        pageTable = new TranslationEntry[numPhysPages];
        for (int i = 0; i < numPhysPages; i++) {
            pageTable[i] = new TranslationEntry(i, 0, false, false, false, false);
        }
        id = idCounter.plusplus();
        fileDescriptors[0] = UserKernel.console.openForReading();
        fileDescriptors[1] = UserKernel.console.openForWriting();

        childExitStatusLock = new Lock();
    }

    /**
     * Allocate and return a new process of the correct class. The class name is
     * specified by the <tt>nachos.conf</tt> key <tt>Kernel.processClassName</tt>.
     *
     * @return a new process of the correct class.
     */
    public static UserProcess newUserProcess() {
        return (UserProcess) Lib.constructObject(Machine.getProcessClassName());
    }

    /**
     * Execute the specified program with the specified arguments. Attempts to load
     * the program, and then forks a thread to run it.
     *
     * @param name the name of the file containing the executable.
     * @param args the arguments to pass to the executable.
     * @return <tt>true</tt> if the program was successfully executed.
     */
    public boolean execute(String name, String[] args) {
        if (!load(name, args))
            return false;

        thread = new UThread(this);
        thread.setName(name).fork();

        return true;
    }

    /**
     * Save the state of this process in preparation for a context switch. Called by
     * <tt>UThread.saveState()</tt>.
     */
    public void saveState() {
    }

    /**
     * Restore the state of this process after a context switch. Called by
     * <tt>UThread.restoreState()</tt>.
     */
    public void restoreState() {
        Machine.processor().setPageTable(pageTable);
    }

    /**
     * Read a null-terminated string from this process's virtual memory. Read at
     * most <tt>maxLength + 1</tt> bytes from the specified address, search for the
     * null terminator, and convert it to a <tt>java.lang.String</tt>, without
     * including the null terminator. If no null terminator is found, returns
     * <tt>null</tt>.
     *
     * @param vaddr     the starting virtual address of the null-terminated string.
     * @param maxLength the maximum number of characters in the string, not
     *                  including the null terminator.
     * @return the string read, or <tt>null</tt> if no null terminator was found.
     */
    public String readVirtualMemoryString(int vaddr, int maxLength) {
        Lib.assertTrue(maxLength >= 0);

        byte[] bytes = new byte[maxLength + 1];

        int bytesRead = readVirtualMemory(vaddr, bytes);

        for (int length = 0; length < bytesRead; length++) {
            if (bytes[length] == 0)
                return new String(bytes, 0, length);
        }

        return null;
    }

    /**
     * Transfer data from this process's virtual memory to all of the specified
     * array. Same as <tt>readVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param vaddr the first byte of virtual memory to read.
     * @param data  the array where the data will be stored.
     * @return the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data) {
        return readVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from this process's virtual memory to the specified array. This
     * method handles address translation details. This method must <i>not</i>
     * destroy the current process if an error occurs, but instead should return the
     * number of bytes successfully copied (or zero if no data could be copied).
     *
     * @param vaddr  the first byte of virtual memory to read.
     * @param data   the array where the data will be stored.
     * @param offset the first byte to write in the array.
     * @param length the number of bytes to transfer from virtual memory to the
     *               array.
     * @return the number of bytes successfully transferred.
     */
    public int readVirtualMemory(int vaddr, byte[] data, int offset, int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();
        if (vaddr < 0 || vaddr >= memory.length)
            return 0;

        int endVAddr = vaddr + length - 1;
        // the virtual page of the first byte to copy
        int startVPN = Machine.processor().pageFromAddress(vaddr);
        // the virtual page of the last byte to copy
        int endVPN = Machine.processor().pageFromAddress(endVAddr);

        int copied = 0;
        for (int i = startVPN; i <= endVPN; i++) {
            if (!pageTable[i].valid) {
                break;
            }

            int startOffset = (i == startVPN) ? Machine.processor().offsetFromAddress(vaddr) : 0;
            int endOffset = (i == endVPN) ? Machine.processor().offsetFromAddress(endVAddr) : pageSize - 1;
            int amount = endOffset - startOffset + 1;

            int paddr = Machine.processor().makeAddress(pageTable[i].ppn, startOffset);
            System.arraycopy(memory, paddr, data, offset + copied, amount);

            copied += amount;
        }
        return copied;
    }

    /**
     * Transfer all data from the specified array to this process's virtual memory.
     * Same as <tt>writeVirtualMemory(vaddr, data, 0, data.length)</tt>.
     *
     * @param vaddr the first byte of virtual memory to write.
     * @param data  the array containing the data to transfer.
     * @return the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data) {
        return writeVirtualMemory(vaddr, data, 0, data.length);
    }

    /**
     * Transfer data from the specified array to this process's virtual memory. This
     * method handles address translation details. This method must <i>not</i>
     * destroy the current process if an error occurs, but instead should return the
     * number of bytes successfully copied (or zero if no data could be copied).
     *
     * @param vaddr  the first byte of virtual memory to write.
     * @param data   the array containing the data to transfer.
     * @param offset the first byte to transfer from the array.
     * @param length the number of bytes to transfer from the array to virtual
     *               memory.
     * @return the number of bytes successfully transferred.
     */
    public int writeVirtualMemory(int vaddr, byte[] data, int offset, int length) {
        Lib.assertTrue(offset >= 0 && length >= 0 && offset + length <= data.length);

        byte[] memory = Machine.processor().getMemory();
        if (vaddr < 0 || vaddr >= memory.length)
            return 0;

        int endVAddr = vaddr + length - 1;
        // the virtual page of the first byte to copy to
        int startVPN = Machine.processor().pageFromAddress(vaddr);
        // the virtual page of the last byte to copy to
        int endVPN = Machine.processor().pageFromAddress(endVAddr);

        int copied = 0;
        for (int i = startVPN; i <= endVPN; i++) {
            if (!pageTable[i].valid) {
                break;
            }

            int startOffset = (i == startVPN) ? Machine.processor().offsetFromAddress(vaddr) : 0;
            int endOffset = (i == endVPN) ? Machine.processor().offsetFromAddress(endVAddr) : pageSize - 1;
            int amount = endOffset - startOffset + 1;

            int paddr = Machine.processor().makeAddress(pageTable[i].ppn, startOffset);
            System.arraycopy(data, offset + copied, memory, paddr, amount);

            copied += amount;
        }
        return copied;
    }

    /**
     * Allocate physical memory for n contiguous virtual pages.
     */
    protected int allocatePages(int n, boolean readOnly) {
        LinkedList<Integer> pages = UserKernel.alloc(n);
        if (pages.size() == n) {
            for (int i = 0; i < n; i++) {
                int index = numPages++;
                pageTable[index].valid = true;
                pageTable[index].ppn = pages.pop();
                pageTable[index].readOnly = readOnly;
            }
            return n;
        } else {
            for (int page : pages) {
                UserKernel.free(page);
            }
            return -1;
        }
    }

    protected void freePages() {
        for (int i = 0; i < pageTable.length; i++)
            if (pageTable[i].valid) {
                UserKernel.free(pageTable[i].ppn);
                pageTable[i].valid = false;
            }
        numPages = 0;
    }
    protected void closeFiles(){
        for (int i = 0; i < 16; i++) {
            if (fileDescriptors[i] != null) {
                fileDescriptors[i].close();
                fileDescriptors[i] = null;
            }
        }
    }

    /**
     * Load the executable with the specified name into this process, and prepare to
     * pass it the specified arguments. Opens the executable, reads its header
     * information, and copies sections and arguments into this process's virtual
     * memory.
     *
     * @param name the name of the file containing the executable.
     * @param args the arguments to pass to the executable.
     * @return <tt>true</tt> if the executable was successfully loaded.
     */
    private boolean load(String name, String[] args) {
        Lib.debug(dbgProcess, "UserProcess.load(\"" + name + "\")");

        OpenFile executable = ThreadedKernel.fileSystem.open(name, false);
        if (executable == null) {
            Lib.debug(dbgProcess, "\topen failed");
            return false;
        }

        try {
            coff = new Coff(executable);
        } catch (EOFException e) {
            executable.close();
            Lib.debug(dbgProcess, "\tcoff load failed");
            return false;
        }

        // make sure the sections are contiguous and start at page 0
        numPages = 0;
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);
            if (section.getFirstVPN() != numPages) {
                coff.close();
                Lib.debug(dbgProcess, "\tfragmented executable");
                return false;
            }

            if (allocatePages(section.getLength(), section.isReadOnly()) == -1) {
                freePages();
                return false;
            }
        }

        // make sure the argv array will fit in one page
        byte[][] argv = new byte[args.length][];
        int argsSize = 0;
        for (int i = 0; i < args.length; i++) {
            argv[i] = args[i].getBytes();
            // 4 bytes for argv[] pointer; then string plus one for null byte
            argsSize += 4 + argv[i].length + 1;
        }
        if (argsSize > pageSize) {
            coff.close();
            Lib.debug(dbgProcess, "\targuments too long");
            return false;
        }

        // program counter initially points at the program entry point
        initialPC = coff.getEntryPoint();

        // next comes the stack; stack pointer initially points to top of it
        // and finally reserve 1 page for arguments
        if (allocatePages(stackPages + 1, false) == -1) {
            freePages();
            return false;
        }
        initialSP = numPages * pageSize;

        if (!loadSections())
            return false;

        // store arguments in last page
        int entryOffset = (numPages - 1) * pageSize;
        int stringOffset = entryOffset + args.length * 4;

        this.argc = args.length;
        this.argv = entryOffset;
        for (int i = 0; i < argv.length; i++) {
            byte[] stringOffsetBytes = Lib.bytesFromInt(stringOffset);

            Lib.assertTrue(writeVirtualMemory(entryOffset, stringOffsetBytes) == 4);
            entryOffset += 4;
            Lib.assertTrue(writeVirtualMemory(stringOffset, argv[i]) == argv[i].length);
            stringOffset += argv[i].length;
            Lib.assertTrue(writeVirtualMemory(stringOffset, new byte[] { 0 }) == 1);
            stringOffset += 1;
        }

        return true;
    }

    /**
     * Allocates memory for this process, and loads the COFF sections into memory.
     * If this returns successfully, the process will definitely be run (this is the
     * last step in process initialization that can fail).
     *
     * @return <tt>true</tt> if the sections were successfully loaded.
     */
    protected boolean loadSections() {
        if (numPages > Machine.processor().getNumPhysPages()) {
            coff.close();
            Lib.debug(dbgProcess, "\tinsufficient physical memory");
            return false;
        }

        // load sections
        for (int s = 0; s < coff.getNumSections(); s++) {
            CoffSection section = coff.getSection(s);

            Lib.debug(dbgProcess,
                    "\tinitializing " + section.getName() + " section (" + section.getLength() + " pages)");

            for (int i = 0; i < section.getLength(); i++) {
                int vpn = section.getFirstVPN() + i;

                section.loadPage(i, pageTable[vpn].ppn);
            }
        }
        return true;
    }

    /**
     * Release any resources allocated by <tt>loadSections()</tt>.
     */
    protected void unloadSections() {
        freePages();
        closeFiles();
        coff.close();
    }

    /**
     * Initialize the processor's registers in preparation for running the program
     * loaded into this process. Set the PC register to point at the start function,
     * set the stack pointer register to point at the top of the stack, set the A0
     * and A1 registers to argc and argv, respectively, and initialize all other
     * registers to 0.
     */
    public void initRegisters() {
        Processor processor = Machine.processor();

        // by default, everything's 0
        for (int i = 0; i < processor.numUserRegisters; i++)
            processor.writeRegister(i, 0);

        // initialize PC and SP according
        processor.writeRegister(Processor.regPC, initialPC);
        processor.writeRegister(Processor.regSP, initialSP);

        // initialize the first two argument registers to argc and argv
        processor.writeRegister(Processor.regA0, argc);
        processor.writeRegister(Processor.regA1, argv);
    }

    /*
     * Halt the Nachos machine by calling Machine.halt(). Only the root process (the
     * first process, executed by UserKernel.run()) should be allowed to execute
     * this syscall. Any other process should ignore the syscall and return
     * immediately.
     */
    private int handleHalt() {
        if (id == 0) {
            Machine.halt();
        } else {
            Lib.debug(dbgProcess, "handleHalt: Only the root process may halt");
        }
        // Lib.assertNotReached("Machine.halt() did not halt machine!");
        return -1;
    }

    /**
     * Terminate the current process immediately. Any open file descriptors
     * belonging to the process are closed. Any children of the process no longer
     * have a parent process.
     *
     * status is returned to the parent process as this process's exit status and
     * can be collected using the join syscall. A process exiting normally should
     * (but is not required to) set status to 0.
     *
     * exit() never returns.
     */
    private int handleExit(int status) {
        for(UserProcess p: children.values()){
            p.parent = null;
        }

        if (parent != null) {
            parent.childExitStatusLock.acquire();
            parent.childExitStatus.put(id, status);
            parent.childExitStatusLock.release();
        }

        unloadSections();

        int processesLeft = processCounter.minusminus() - 1;

        if (processesLeft > 0) {
            UThread.finish();
        } else {
            Kernel.kernel.terminate();
        }

        Lib.assertNotReached("handleExit unexpectedly returned");
        return 0;
    }

    /**
     * Execute the program stored in the specified file, with the specified
     * arguments, in a new child process. The child process has a new unique process
     * ID, and starts with stdin opened as file descriptor 0, and stdout opened as
     * file descriptor 1.
     *
     * file is a null-terminated string that specifies the name of the file
     * containing the executable. Note that this string must include the ".coff"
     * extension.
     *
     * argc specifies the number of arguments to pass to the child process. This
     * number must be non-negative.
     *
     * argv is an array of pointers to null-terminated strings that represent the
     * arguments to pass to the child process. argv[0] points to the first argument,
     * and argv[argc-1] points to the last argument.
     *
     * exec() returns the child process's process ID, which can be passed to join().
     * On error, returns -1.
     */
    private int handleExec(int file, int argc, int argv) {
        if (argc < 0) {
            Lib.debug(dbgProcess, "handleExec: argc must be non-negative");
            return -1;
        }
        String fileName = readVirtualMemoryString(file, 256);
        if (fileName == null) {
            Lib.debug(dbgProcess, "handleExec: filename memory read failed");
            return -1;
        }
        if (!fileName.endsWith(".coff")) {
            Lib.debug(dbgProcess, "handleExec: File name must end in '.coff'");
            return -1;
        }

        String[] args = new String[argc];
        for (int i = 0; i < argc; i++) {
            byte[] buffer = new byte[4];
            if (readVirtualMemory(argv + i * 4, buffer) != 4) {
                Lib.debug(dbgProcess, "handleExec: arg pointer memory read failed");
                return -1;
            }
            String arg = readVirtualMemoryString(Lib.bytesToInt(buffer, 0), 256);
            if (arg == null) {
                Lib.debug(dbgProcess, "handleExec: arg string memory read failed");
                return -1;
            }
            args[i] = arg;
        }
        UserProcess child = newUserProcess();
        if (!child.execute(fileName, args)) {
            Lib.debug(dbgProcess, "Child execution failed");
            return -1;
        }
        child.parent = this;
        children.put(child.id, child);
        return child.id;
    }

    /**
     * Suspend execution of the current process until the child process specified by
     * the processID argument has exited. If the child has already exited by the
     * time of the call, returns immediately. When the current process resumes, it
     * disowns the child process, so that join() cannot be used on that process
     * again.
     *
     * processID is the process ID of the child process, returned by exec().
     *
     * status points to an integer where the exit status of the child process will
     * be stored. This is the value the child passed to exit(). If the child exited
     * because of an unhandled exception, the value stored is not defined.
     *
     * If the child exited normally, returns 1. If the child exited as a result of
     * an unhandled exception, returns 0. If processID does not refer to a child
     * process of the current process, returns -1.
     */
    private int handleJoin(int id, int statusvaddr) {
        UserProcess child = children.get(id);

        if (child == null) {
            Lib.debug(dbgProcess, "handleJoin: process id " + id + " is not a child");
            return -1;
        }
        
        child.thread.join();

        children.remove(id);
        child.parent = null;

        Integer status;
        childExitStatusLock.acquire();
        status = childExitStatus.get(id);
        childExitStatus.remove(id);
        childExitStatusLock.release();

        if (status == null) {
            return 0;
        }

        if (writeVirtualMemory(statusvaddr, Lib.bytesFromInt(status)) != 4) {
            // it's okay if this fails
            Lib.debug(dbgProcess, "handleJoin: Memory write failed");
        }
        return 1;
    }

    /**
     * Attempt to open the named disk file, creating it if it does not exist, and
     * return a file descriptor that can be used to access the file.
     *
     * Note that creat() can only be used to create files on disk; creat() will
     * never return a file descriptor referring to a stream.
     *
     * Returns the new file descriptor, or -1 if an error occurred.
     */
    private int handleCreate(int vaddr) {
        String fileName = readVirtualMemoryString(vaddr, 256);
        if (fileName == null) {
            Lib.debug(dbgProcess, "handleCreate: Memory read failed");
            return -1;
        }

        int descriptor;
        for (descriptor = 0; descriptor < 16; descriptor++) {
            if (fileDescriptors[descriptor] == null) {
                break;
            }
        }

        if (descriptor == 16) {
            Lib.debug(dbgProcess, "handleCreate: File descriptor capacity reached");
            return -1;
        }

        OpenFile file = ThreadedKernel.fileSystem.open(fileName, true);
        if (file == null) {
            Lib.debug(dbgProcess, "handleCreate: Filesystem open failed");
            return -1;
        } else {
            fileDescriptors[descriptor] = file;
            return descriptor;
        }
    }

    /**
     * Attempt to open the named file and return a file descriptor.
     *
     * Note that open() can only be used to open files on disk; open() will never
     * return a file descriptor referring to a stream.
     *
     * Returns the new file descriptor, or -1 if an error occurred.
     */
    private int handleOpen(int vaddr) {
        String fileName = readVirtualMemoryString(vaddr, 256);
        if (fileName == null) {
            Lib.debug(dbgProcess, "handleOpen: Memory read failed");
            return -1;
        }

        int descriptor;
        for (descriptor = 0; descriptor < 16; descriptor++) {
            if (fileDescriptors[descriptor] == null) {
                break;
            }
        }

        if (descriptor == 16) {
            Lib.debug(dbgProcess, "handleOpen: File descriptor capacity reached");
            return -1;
        }

        OpenFile file = ThreadedKernel.fileSystem.open(fileName, false);
        if (file == null) {
            Lib.debug(dbgProcess, "handleOpen: Filesystem open failed");
            return -1;
        } else {
            fileDescriptors[descriptor] = file;
            return descriptor;
        }
    }

    /**
     * Helper function to check a user provided descriptor and return the associated
     * file if valid.
     * 
     */
    private OpenFile openDescriptor(int descriptor, String debugPrefix) {
        if (descriptor < 0 || descriptor > 15) {
            Lib.debug(dbgProcess, debugPrefix + ": Descriptor out of range");
            return null;
        }
        if (fileDescriptors[descriptor] == null) {
            Lib.debug(dbgProcess, debugPrefix + ": Descriptor is null");
            return null;
        }
        return fileDescriptors[descriptor];
    }

    /**
     * Attempt to read up to count bytes into buffer from the file or stream
     * referred to by fileDescriptor.
     *
     * On success, the number of bytes read is returned. If the file descriptor
     * refers to a file on disk, the file position is advanced by this number.
     *
     * It is not necessarily an error if this number is smaller than the number of
     * bytes requested. If the file descriptor refers to a file on disk, this
     * indicates that the end of the file has been reached. If the file descriptor
     * refers to a stream, this indicates that the fewer bytes are actually
     * available right now than were requested, but more bytes may become available
     * in the future. Note that read() never waits for a stream to have more data;
     * it always returns as much as possible immediately.
     *
     * On error, -1 is returned, and the new file position is undefined. This can
     * happen if fileDescriptor is invalid, if part of the buffer is read-only or
     * invalid, or if a network stream has been terminated by the remote host and no
     * more data is available.
     */
    private int handleRead(int descriptor, int buffer, int size) {
        OpenFile file = openDescriptor(descriptor, "handleRead");
        if (file == null) {
            return -1;
        }

        if (size < 0) {
            Lib.debug(dbgProcess, "handleRead: Cannot read negative bytes");
            return -1;
        }

        byte[] data = new byte[size];
        int length = file.read(data, 0, size);

        if (length == -1) {
            Lib.debug(dbgProcess, "handleRead: File read failed");
            return -1;
        }

        int ret = writeVirtualMemory(buffer, data, 0, length);
        if (ret == -1) {
            Lib.debug(dbgProcess, "handleRead: Memory write failed");
            return -1;
        }
        return ret;
    }

    /**
     * Attempt to write up to count bytes from buffer to the file or stream referred
     * to by fileDescriptor. write() can return before the bytes are actually
     * flushed to the file or stream. A write to a stream can block, however, if
     * kernel queues are temporarily full.
     *
     * On success, the number of bytes written is returned (zero indicates nothing
     * was written), and the file position is advanced by this number. It IS an
     * error if this number is smaller than the number of bytes requested. For disk
     * files, this indicates that the disk is full. For streams, this indicates the
     * stream was terminated by the remote host before all the data was transferred.
     *
     * On error, -1 is returned, and the new file position is undefined. This can
     * happen if fileDescriptor is invalid, if part of the buffer is invalid, or if
     * a network stream has already been terminated by the remote host.
     */
    private int handleWrite(int descriptor, int buffer, int size) {
        if(size < 0){
            Lib.debug(dbgProcess, "handleWrite: Cannot write negative bytes");
            return -1;
        }

        OpenFile file = openDescriptor(descriptor, "handleWrite");
        if (file == null) {
            return -1;
        }

        byte[] data = new byte[size];
        int length = readVirtualMemory(buffer, data, 0, size);
        if (length == -1) {
            Lib.debug(dbgProcess, "handleWrite: Memory read failed");
            return -1;
        }
        if (length != size) {
            Lib.debug(dbgProcess, "handleWrite: Buffer doesn't contain that many bytes");
            return -1;
        }
        int ret = file.write(data, 0, length);
        if (ret == -1) {
            Lib.debug(dbgProcess, "handleWrite: File write failed");
            return -1;
        }
        if (ret != size) {
            Lib.debug(dbgProcess,
                    "handleWrite: Wrong number of bytes written (" + ret + "written, " + size + "expected");
            return -1;
        }
        return ret;
    }

    /**
     * Close a file descriptor, so that it no longer refers to any file or stream
     * and may be reused.
     *
     * If the file descriptor refers to a file, all data written to it by write()
     * will be flushed to disk before close() returns. If the file descriptor refers
     * to a stream, all data written to it by write() will eventually be flushed
     * (unless the stream is terminated remotely), but not necessarily before
     * close() returns.
     *
     * The resources associated with the file descriptor are released. If the
     * descriptor is the last reference to a disk file which has been removed using
     * unlink, the file is deleted (this detail is handled by the file system
     * implementation).
     *
     * Returns 0 on success, or -1 if an error occurred.
     */
    private int handleClose(int descriptor) {
        OpenFile file = openDescriptor(descriptor, "handleWrite");
        if (file == null) {
            return -1;
        }
        fileDescriptors[descriptor].close();
        fileDescriptors[descriptor] = null;
        return 0;
    }

    /**
     * Delete a file from the file system. If no processes have the file open, the
     * file is deleted immediately and the space it was using is made available for
     * reuse.
     *
     * If any processes still have the file open, the file will remain in existence
     * until the last file descriptor referring to it is closed. However, creat()
     * and open() will not be able to return new file descriptors for the file until
     * it is deleted.
     *
     * Returns 0 on success, or -1 if an error occurred.
     */
    private int handleUnlink(int vaddr) {
        String fileName = readVirtualMemoryString(vaddr, 256);
        if (fileName == null) {
            Lib.debug(dbgProcess, "handleUnlink: Memory read failed");
            return -1;
        }
        boolean success = ThreadedKernel.fileSystem.remove(fileName);
        if (!success) {
            Lib.debug(dbgProcess, "handleUnlink: File system remove failed");
            return -1;
        }
        return 0;
    }

    private static final int syscallHalt = 0, syscallExit = 1, syscallExec = 2, syscallJoin = 3, syscallCreate = 4,
            syscallOpen = 5, syscallRead = 6, syscallWrite = 7, syscallClose = 8, syscallUnlink = 9;

    /**
     * Handle a syscall exception. Called by <tt>handleException()</tt>. The
     * <i>syscall</i> argument identifies which syscall the user executed:
     *
     * <table>
     * <tr>
     * <td>syscall#</td>
     * <td>syscall prototype</td>
     * </tr>
     * <tr>
     * <td>0</td>
     * <td><tt>void halt();</tt></td>
     * </tr>
     * <tr>
     * <td>1</td>
     * <td><tt>void exit(int status);</tt></td>
     * </tr>
     * <tr>
     * <td>2</td>
     * <td><tt>int  exec(char *name, int argc, char **argv);
     * 								</tt></td>
     * </tr>
     * <tr>
     * <td>3</td>
     * <td><tt>int  join(int pid, int *status);</tt></td>
     * </tr>
     * <tr>
     * <td>4</td>
     * <td><tt>int  creat(char *name);</tt></td>
     * </tr>
     * <tr>
     * <td>5</td>
     * <td><tt>int  open(char *name);</tt></td>
     * </tr>
     * <tr>
     * <td>6</td>
     * <td><tt>int  read(int fd, char *buffer, int size);
     *								</tt></td>
     * </tr>
     * <tr>
     * <td>7</td>
     * <td><tt>int  write(int fd, char *buffer, int size);
     *								</tt></td>
     * </tr>
     * <tr>
     * <td>8</td>
     * <td><tt>int  close(int fd);</tt></td>
     * </tr>
     * <tr>
     * <td>9</td>
     * <td><tt>int  unlink(char *name);</tt></td>
     * </tr>
     * </table>
     * 
     * @param syscall the syscall number.
     * @param a0      the first syscall argument.
     * @param a1      the second syscall argument.
     * @param a2      the third syscall argument.
     * @param a3      the fourth syscall argument.
     * @return the value to be returned to the user.
     */
    public int handleSyscall(int syscall, int a0, int a1, int a2, int a3) {
        switch (syscall) {
            case syscallHalt:
                return handleHalt();
            case syscallExit:
                return handleExit(a0);
            case syscallExec:
                return handleExec(a0, a1, a2);
            case syscallJoin:
                return handleJoin(a0, a1);
            case syscallCreate:
                return handleCreate(a0);
            case syscallOpen:
                return handleOpen(a0);
            case syscallRead:
                return handleRead(a0, a1, a2);
            case syscallWrite:
                return handleWrite(a0, a1, a2);
            case syscallClose:
                return handleClose(a0);
            case syscallUnlink:
                return handleUnlink(a0);
            default:
                Lib.debug(dbgProcess, "Unknown syscall " + syscall);
                Lib.assertNotReached("Unknown system call!");
        }
        return 0;
    }

    /**
     * Handle a user exception. Called by <tt>UserKernel.exceptionHandler()</tt>.
     * The <i>cause</i> argument identifies which exception occurred; see the
     * <tt>Processor.exceptionZZZ</tt> constants.
     *
     * @param cause the user exception that occurred.
     */
    public void handleException(int cause) {
        Processor processor = Machine.processor();

        switch (cause) {
            case Processor.exceptionSyscall:
                int result = handleSyscall(processor.readRegister(Processor.regV0),
                        processor.readRegister(Processor.regA0), processor.readRegister(Processor.regA1),
                        processor.readRegister(Processor.regA2), processor.readRegister(Processor.regA3));
                processor.writeRegister(Processor.regV0, result);
                processor.advancePC();
                break;

            default:
                Lib.debug(dbgProcess, "Unexpected exception: " + Processor.exceptionNames[cause] + "; process terminated");
                //kind of a hack, just force it to exit without sending a exit status to parent
                parent = null;
                handleExit(-1);
                Lib.assertNotReached("Exception threw");
        }
    }

    /** The program being run by this process. */
    protected Coff coff;

    /** This process's page table. */
    protected TranslationEntry[] pageTable;
    /** The number of contiguous pages occupied by the program. */
    protected int numPages;

    /** The number of pages in the program's stack. */
    protected final int stackPages = 8;

    private int initialPC, initialSP;
    private int argc, argv;

    private static final int pageSize = Processor.pageSize;
    private static final char dbgProcess = 'a';

    protected static AtomicCounter idCounter = new AtomicCounter(0);
    //root process will increment this but not counted
    protected static AtomicCounter processCounter = new AtomicCounter(-1);

    protected int id = -1;
    protected UThread thread = null;
    protected OpenFile[] fileDescriptors = new OpenFile[16];

    protected UserProcess parent = null;
    protected HashMap<Integer, UserProcess> children = new HashMap<Integer, UserProcess>();
    protected HashMap<Integer, Integer> childExitStatus = new HashMap<Integer, Integer>();
    protected Lock childExitStatusLock;
}
