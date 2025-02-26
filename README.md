![Flowchart](https://github.com/gptshubham595/InstagramReelsDesign/blob/main/flowchart.png)

## Backend System Design
Let's design a backend for this chunked video streaming system:

### Core Components:

Video Processing Service: Splits videos into chunks, generates multiple quality versions
Metadata Service: Manages video information and chunk manifests
API Gateway: Handles client requests and orchestrates responses


### Data Flow:

When a video is uploaded, it's processed into multiple quality levels and chunked
Each chunk is stored separately with proper naming conventions
A manifest file (JSON) is created listing all chunks and their details
When a client requests the feed, it receives video metadata and chunk information
The client decides which chunks to download based on viewing behavior


### Streaming Protocol: HTTP-based adaptive streaming (HLS or DASH)

HLS uses .m3u8 playlist files and .ts segment files
DASH uses MPD (Media Presentation Description) files and MP4 segments

### Setup 

- npm i // to install dependencies
- npm start // to start the server
- create a folder video outside and web and put all videos there
- npm process-video.js // to convert videos into low, medium, high chunks each of 3sec

![API FEED](https://github.com/gptshubham595/InstagramReelsDesign/blob/main/api-feed.png)
![PROCESS VIDEO](https://github.com/gptshubham595/InstagramReelsDesign/blob/main/process-video.png)

## API Endpoints:
- GET /api/feed?page=0&pageSize=5 - Get paginated video feed
- GET /api/videos/:videoId - Get full video details with all chunks
- GET /process - Get full video details with all chunks
- GET /api/status - Check server status

