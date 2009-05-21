package gov.nasa.arc.geocam;

interface IGeoCamService {
	void addToUploadQueue(in String uri);
	int getUploadQueueLength();
}
