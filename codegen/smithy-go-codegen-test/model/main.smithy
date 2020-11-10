$version: "1.0"
namespace example.weather

use smithy.test#httpRequestTests
use smithy.test#httpResponseTests

/// Provides weather forecasts.
@fakeProtocol
@paginated(inputToken: "nextToken", outputToken: "nextToken", pageSize: "pageSize")
service Weather {
    version: "2006-03-01",
    resources: [City],
    operations: [GetCurrentTime, __789BadName]
}

resource City {
    identifiers: { cityId: CityId },
    read: GetCity,
    list: ListCities,
    resources: [Forecast, CityImage],
    operations: [GetCityAnnouncements]
}

resource Forecast {
    identifiers: { cityId: CityId },
    read: GetForecast,
}

resource CityImage {
    identifiers: { cityId: CityId },
    read: GetCityImage,
}

// "pattern" is a trait.
@pattern("^[A-Za-z0-9 ]+$")
string CityId

@readonly
@http(method: "GET", uri: "/cities/{cityId}")
operation GetCity {
    input: GetCityInput,
    output: GetCityOutput,
    errors: [NoSuchResource]
}

@http(method: "POST", uri: "/BadName/{__123abc}")
operation __789BadName {
    input: __BadNameCont,
    output: __BadNameCont,
    errors: [NoSuchResource]
}

// Tests that HTTP protocol tests are generated.
apply GetCity @httpRequestTests([
    {
        id: "WriteGetCityAssertions",
        documentation: "Does something",
        protocol: "example.weather#fakeProtocol",
        method: "GET",
        uri: "/cities/123",
        body: "",
        params: {
            cityId: "123"
        }
    }
])

apply GetCity @httpResponseTests([
    {
        id: "WriteGetCityResponseAssertions",
        documentation: "Does something",
        protocol: "example.weather#fakeProtocol",
        code: 200,
        body: """
            {
                "name": "Seattle",
                "coordinates": {
                    "latitude": 12.34,
                    "longitude": -56.78
                },
                "city": {
                    "cityId": "123",
                    "name": "Seattle",
                    "number": "One",
                    "case": "Upper"
                }
            }""",
        bodyMediaType: "application/json",
        params: {
            name: "Seattle",
            coordinates: {
                latitude: 12.34,
                longitude: -56.78
            },
            city: {
                cityId: "123",
                name: "Seattle",
                number: "One",
                case: "Upper"
            }
        }
    }
])

/// The input used to get a city.
structure GetCityInput {
    // "cityId" provides the identifier for the resource and
    // has to be marked as required.
    @required
    @httpLabel
    cityId: CityId,
}

structure __BadNameCont {
    @required
    @httpLabel
    __123abc: String,

    Member: __456efg,
}

structure __456efg {
    __123foo: String,
}

structure GetCityOutput {
    // "required" is used on output to indicate if the service
    // will always provide a value for the member.
    @required
    name: String,

    @required
    coordinates: CityCoordinates,

    city: CitySummary,
}

// This structure is nested within GetCityOutput.
structure CityCoordinates {
    @required
    latitude: PrimitiveFloat,

    @required
    longitude: Float,
}

/// Error encountered when no resource could be found.
@error("client")
@httpError(404)
structure NoSuchResource {
    /// The type of resource that was not found.
    @required
    resourceType: String,

    message: String,
}

apply NoSuchResource @httpResponseTests([
    {
        id: "WriteNoSuchResourceAssertions",
        documentation: "Does something",
        protocol: "example.weather#fakeProtocol",
        code: 404,
        body: """
            {
                "resourceType": "City",
                "message": "Your custom message"
            }""",
        bodyMediaType: "application/json",
        params: {
            resourceType: "City",
            message: "Your custom message"
        }
    }
])

// The paginated trait indicates that the operation may
// return truncated results.
@readonly
@paginated(items: "items")
@http(method: "GET", uri: "/cities")
operation ListCities {
    input: ListCitiesInput,
    output: ListCitiesOutput
}

apply ListCities @httpRequestTests([
    {
        id: "WriteListCitiesAssertions",
        documentation: "Does something",
        protocol: "example.weather#fakeProtocol",
        method: "GET",
        uri: "/cities",
        body: "",
        queryParams: ["pageSize=50"],
        forbidQueryParams: ["nextToken"],
        params: {
            pageSize: 50
        }
    }
])

integer DefaultInteger
boolean DefaultBool

structure ListCitiesInput {
    @httpQuery("nextToken")
    nextToken: String,

    @httpQuery("aString")
    aString: String,

    @httpQuery("defaultBool")
    defaultBool: DefaultBool,

    @httpQuery("boxedBool")
    boxedBool: Boolean,

    @httpQuery("defaultNumber")
    defaultNumber: DefaultInteger,

    @httpQuery("boxedNumber")
    boxedNumber: Integer,

    @httpQuery("someEnum")
    someEnum: SimpleYesNo,

    @httpQuery("pageSize")
    pageSize: Integer
}

structure ListCitiesOutput {
    nextToken: String,

    someEnum: SimpleYesNo,
    aString: String,
    defaultBool: DefaultBool,
    boxedBool: Boolean,
    defaultNumber: DefaultInteger,
    boxedNumber: Integer,

    @required
    items: CitySummaries,
    sparseItems: SparseCitySummaries,
}

// CitySummaries is a list of CitySummary structures.
list CitySummaries {
    member: CitySummary
}

// CitySummaries is a sparse list of CitySummary structures.
@sparse
list SparseCitySummaries {
    member: CitySummary
}

// CitySummary contains a reference to a City.
@references([{resource: City}])
structure CitySummary {
    @required
    cityId: CityId,

    @required
    name: String,

    number: String,
    case: String,
}

@readonly
@http(method: "GET", uri: "/current-time")
operation GetCurrentTime {
    output: GetCurrentTimeOutput
}

structure GetCurrentTimeOutput {
    @required
    time: Timestamp
}

@readonly
@http(method: "GET", uri: "/cities/{cityId}/forecast")
operation GetForecast {
    input: GetForecastInput,
    output: GetForecastOutput
}

// "cityId" provides the only identifier for the resource since
// a Forecast doesn't have its own.
structure GetForecastInput {
    @required
    @httpLabel
    cityId: CityId,
}

structure GetForecastOutput {
    chanceOfRain: Float,
    precipitation: Precipitation,
}

union Precipitation {
    rain: PrimitiveBoolean,
    sleet: PrimitiveBoolean,
    hail: StringMap,
    snow: SimpleYesNo,
    mixed: TypedYesNo,
    other: OtherStructure,
    blob: Blob,
    foo: example.weather.nested#Foo,
    baz: example.weather.nested.more#Baz,
}

structure OtherStructure {}

@enum([{value: "YES"}, {value: "NO"}])
string SimpleYesNo

@enum([{value: "YES", name: "YES"}, {value: "NO", name: "NO"}])
string TypedYesNo

map StringMap {
    key: String,
    value: String,
}

@readonly
@http(method: "GET", uri: "/cities/{cityId}/image")
operation GetCityImage {
    input: GetCityImageInput,
    output: GetCityImageOutput,
    errors: [NoSuchResource]
}

structure GetCityImageInput {
    @required @httpLabel
    cityId: CityId,
}

structure GetCityImageOutput {
    @httpPayload
    image: CityImageData,
}

@streaming
blob CityImageData

@readonly
@http(method: "GET", uri: "/cities/{cityId}/announcements")
operation GetCityAnnouncements {
    input: GetCityAnnouncementsInput,
    output: GetCityAnnouncementsOutput,
    errors: [NoSuchResource]
}


structure GetCityAnnouncementsInput {
    @required
    @httpLabel
    cityId: CityId,
}

structure GetCityAnnouncementsOutput {
    @httpHeader("x-last-updated")
    lastUpdated: Timestamp,

    @httpPayload
    announcements: Announcements
}

@streaming
union Announcements {
    police: Message,
    fire: Message,
    health: Message
}

structure Message {
    message: String,
    author: String
}

// Define a fake protocol trait for use.
@trait
@protocolDefinition
structure fakeProtocol {}
