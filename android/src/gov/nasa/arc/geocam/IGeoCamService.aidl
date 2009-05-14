package gov.nasa.arc.geocam;

interface IGeoCamService {
	boolean addToUploadQueue(int id);
	int getCurrentId();
}
