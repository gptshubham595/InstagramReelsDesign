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
