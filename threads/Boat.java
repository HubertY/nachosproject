package nachos.threads;

import nachos.ag.BoatGrader;

public class Boat {
	static BoatGrader bg;

	static int OahuAdults;
	static int OahuChildren;

	static Semaphore OahuAdultBoatReady;
	static Semaphore OahuChildBoatReady;
	static Semaphore MoloBoatReady;

	static boolean OahuChildRowerSet;
	static Lock OahuChildRowerLock;

	static Semaphore VictoryDeclared;

	public static void selfTest() {
		for (int children = 2; children < 20; children++) {
			BoatGrader b = new BoatGrader();
			for (int adults = 0; adults < 20; adults++) {
				System.out.println("Testing Boat with " + adults + " adults and " + children + " children.");
				begin(adults, children, b);
			}
		}
	}

	public static void begin(int adults, int children, BoatGrader b) {
		// Store the externally generated autograder in a class
		// variable to be accessible by children.
		bg = b;

		OahuAdults = adults;
		OahuChildren = children;

		OahuAdultBoatReady = new Semaphore(0);
		OahuChildBoatReady = new Semaphore(2);
		MoloBoatReady = new Semaphore(0);

		OahuChildRowerSet = false;
		OahuChildRowerLock = new Lock();

		VictoryDeclared = new Semaphore(0);

		for (int i = 0; i < children; i++) {
			new KThread(new Runnable() {
				public void run() {
					ChildItinerary();
				}
			}).setName("c" + i).fork();
		}

		for (int i = 0; i < adults; i++) {
			new KThread(new Runnable() {
				public void run() {
					AdultItinerary();
				}
			}).setName("a" + i).fork();
		}

		VictoryDeclared.P();
	}

	static void AdultItinerary() {
		bg.initializeAdult(); // Required for autograder interface. Must be the first thing called.
		// DO NOT PUT ANYTHING ABOVE THIS LINE.

		OahuAdultBoatReady.P();
		OahuAdults--;
		bg.AdultRowToMolokai();
		MoloBoatReady.V();
	}

	static void ChildItinerary() {
		bg.initializeChild(); // Required for autograder interface. Must be the first thing called.
		// DO NOT PUT ANYTHING ABOVE THIS LINE.
		while (true) {
			OahuChildBoatReady.P();
			OahuChildRowerLock.acquire();
			boolean captain = !OahuChildRowerSet;
			OahuChildRowerSet = !OahuChildRowerSet;
			if (captain) {
				bg.ChildRowToMolokai();
				OahuChildRowerLock.release();
			} else {
				OahuChildRowerLock.release();
				OahuChildren -= 2;
				boolean done = (OahuAdults == 0 && OahuChildren == 0);
				bg.ChildRideToMolokai();
				if (done) {
					VictoryDeclared.V();
				} else {
					MoloBoatReady.V();
				}
			}
			MoloBoatReady.P();
			bg.ChildRowToOahu();
			OahuChildren++;
			if (OahuChildren >= 2) {
				OahuChildBoatReady.V();
				OahuChildBoatReady.V();
			} else {
				OahuAdultBoatReady.V();
			}
		}
	}
}
